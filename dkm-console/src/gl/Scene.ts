import type { Schema, VizCatalog } from '../api/types'

/**
 * The live picture, held entirely in preallocated typed arrays.
 *
 * <p>This is where the console earns its latency budget. Frames arrive on the
 * WebSocket and are decoded straight into these arrays -- no objects per sample,
 * no React state, no re-render. React only ever learns about counters, at a rate
 * a human can read. The animation loop reads these arrays and uploads them to
 * the GPU. A message can therefore go wire-to-pixel without ever passing
 * through the framework, which is the only way "sub-100 ms, sustained" is a
 * design property rather than a hope.
 */

const FRAME_MAGIC = 0x444b4d56
const FRAME_HEADER_BYTES = 24
const RECORD_BYTES = 48

const KIND = {
    NONE: 0, POINT: 1, TRACK: 2, RAY: 3, LINE: 4, CIRCULAR_AREA: 5, RECT_AREA: 6,
} as const

const FLAG_OUTPUT = 1
const FLAG_EMPHASIS = 2

/** Points per ring; a few seconds of a busy scene at full rate. */
const MAX_POINTS = 32768
const POINT_STRIDE = 7 // x, y, r, g, b, birth, size

const MAX_RAYS = 512
const MAX_LINES = 512
/** Mirrors mock_r's kAreaBufferCapacity: areas are standing state, not events. */
const MAX_AREAS = 16
const TRACK_CAPACITY = 512
const MAX_TRACKS = 256

export interface SceneStats {
    points: number
    tracks: number
    sectors: number
    rects: number
    rays: number
    framesReceived: number
    samplesReceived: number
    serverDropped: number
    lastFrameAt: number
    /** Milliseconds between the server stamping a frame and this client decoding it. */
    transportLagMs: number
}

interface Track {
    id: number
    xs: Float32Array
    ys: Float32Array
    count: number
    head: number
    color: [number, number, number]
    lastSeen: number
    lastX: number
    lastY: number
    vx: number
    vy: number
    fromOutput: boolean
}

interface Area {
    key: string
    a: number
    b: number
    c: number
    d: number
    color: [number, number, number]
    fillOpacity: number
    lastSeen: number
    fromOutput: boolean
    link: number
    msgId: number
}

/**
 * Drops expired segments in place, keeping the rest in order.
 *
 * <p>In place because this runs once a frame on a list that can hold a few
 * hundred entries: rebuilding the array every frame would hand the collector
 * a steady drip of garbage for no reason.
 */
function compact(list: Segment[], now: number) {
    let write = 0
    for (let read = 0; read < list.length; read++) {
        const segment = list[read]
        if (now - segment.birth <= segment.lifetime) list[write++] = segment
    }
    list.length = write
}

interface Segment {
    x1: number
    y1: number
    x2: number
    y2: number
    color: [number, number, number]
    birth: number
    dashed: boolean
    link: number
    msgId: number
    heading: number
    fromOutput: boolean
    /** This segment's own fade time, in milliseconds. See {@link Scene.raysLifetimeMs}. */
    lifetime: number
}

/**
 * What the cursor is over. Returned by {@link Scene.pick} and rendered as a
 * tooltip; carries the values the operator would otherwise have to find in the
 * message list, which is the whole point of hovering something on a plan view.
 */
export type PickResult =
    | {
        kind: 'point' | 'track'
        link: number; msgId: number; output: boolean
        x: number; y: number; distance: number; heading: number
        vx: number; vy: number; trackId: number; points: number; ageMs: number
    }
    | {
        kind: 'sector'; link: number; msgId: number; output: boolean
        r0: number; r1: number; h0: number; h1: number
    }
    | {
        kind: 'rect'; link: number; msgId: number; output: boolean
        x0: number; x1: number; y0: number; y1: number
    }
    | {
        kind: 'ray'; link: number; msgId: number; output: boolean
        heading: number; length: number
    }

const DEFAULT_COLOR: [number, number, number] = [0.6, 0.68, 0.78]

/** Same hue, enough darker to read against a pale ground. */
function deepen([r, g, b]: [number, number, number]): [number, number, number] {
    return [r * 0.66, g * 0.66, b * 0.66]
}

/** Shortest distance from a point to a line segment. */
function distanceToSegment(px: number, py: number,
    x1: number, y1: number, x2: number, y2: number): number {
    const dx = x2 - x1
    const dy = y2 - y1
    const lengthSquared = dx * dx + dy * dy
    if (lengthSquared === 0) {
        return Math.hypot(px - x1, py - y1)
    }
    const t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / lengthSquared))
    return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))
}

function parseColor(hex: string | undefined): [number, number, number] {
    if (!hex || !/^#?[0-9a-fA-F]{6}$/.test(hex)) return DEFAULT_COLOR
    const value = hex.replace('#', '')
    return [
        parseInt(value.slice(0, 2), 16) / 255,
        parseInt(value.slice(2, 4), 16) / 255,
        parseInt(value.slice(4, 6), 16) / 255,
    ]
}

export class Scene {
    readonly points = new Float32Array(MAX_POINTS * POINT_STRIDE)
    /**
     * Picking metadata, parallel to the GPU buffer and never uploaded. Kept
     * separate so the vertex layout stays the seven floats the shader wants: the
     * cursor needs values the renderer has no use for.
     */
    private readonly pointInfo = new Int32Array(MAX_POINTS * 3)   // link | msgId | trackId
    private readonly pointGeo = new Float32Array(MAX_POINTS * 4)  // distance, heading, vx, vy
    pointHead = 0
    pointCount = 0
    /** Range written since the last GPU upload, as a half-open ring interval. */
    dirtyFrom = 0
    dirtyCount = 0

    readonly tracks = new Map<number, Track>()
    readonly sectors = new Map<string, Area>()
    readonly rects = new Map<string, Area>()
    rays: Segment[] = []
    lines: Segment[] = []

    /** Fade time for transient marks, in milliseconds. */
    pointLifetimeMs = 8000
    /**
     * Default fade time for rays and lines, overridable per message type by the
     * catalog's {@code style.persistenceMs}.
     *
     * <p>One number cannot serve both kinds of line here. A rotating antenna
     * announces tens of beams a second, so the sweep needs a short tail or it
     * closes into a starburst that hides the picture; a jammer strobe is an
     * event and wants to stay up long enough to be noticed. The catalog already
     * owns how a type is drawn, so it owns this too.
     */
    raysLifetimeMs = 2500

    stats: SceneStats = {
        points: 0, tracks: 0, sectors: 0, rects: 0, rays: 0,
        framesReceived: 0, samplesReceived: 0, serverDropped: 0,
        lastFrameAt: 0, transportLagMs: 0,
    }

    private palette = new Map<number, { color: [number, number, number]; fill: number; dashed: boolean; emphasis: boolean; persistenceMs: number }>()
    private linkNames: string[] = []
    private typeNames = new Map<number, string>()

    /**
     * Builds the (link, msg_id) -> style lookup from the schema and the
     * visualization catalog, so nothing about any specific message type is
     * hard-coded here either.
     */
    configure(schema: Schema, catalog: VizCatalog, darkGround = true) {
        this.palette.clear()
        this.typeNames.clear()
        this.linkNames = []
        const moduleByIndex = new Map<number, string>()
        for (const module of schema.modules) {
            if (module.linkIndex >= 0) {
                moduleByIndex.set(module.linkIndex, module.name)
                this.linkNames[module.linkIndex] = module.name
            }
        }
        for (const message of schema.messages) {
            const mapping = catalog.mappings[message.qualifiedName]
            if (!mapping) continue
            const linkIndex = schema.modules.find((m) => m.name === message.module)?.linkIndex ?? -1
            if (linkIndex < 0) continue
            this.typeNames.set(this.styleKey(linkIndex, message.msgId), message.qualifiedName)
            this.palette.set(this.styleKey(linkIndex, message.msgId), {
                // The catalog's colours are picked to glow on a dark ground. On a pale
                // one the same values are far too light to read, so they are deepened
                // here rather than duplicated in the config -- the mapping stays one
                // source of truth, and the theme stays a client concern.
                color: darkGround ? parseColor(mapping.style?.color)
                    : deepen(parseColor(mapping.style?.color)),
                fill: mapping.style?.fillOpacity ?? 0.1,
                dashed: mapping.style?.dashed ?? false,
                emphasis: mapping.style?.emphasis ?? false,
                persistenceMs: mapping.style?.persistenceMs ?? this.raysLifetimeMs,
            })
        }
    }

    linkName(index: number): string {
        return this.linkNames[index] ?? `link${index}`
    }

    /** Qualified message type for a (link, msg_id) pair, or null if the schema has none. */
    typeName(link: number, msgId: number): string | null {
        return this.typeNames.get(this.styleKey(link, msgId)) ?? null
    }

    private styleKey(link: number, msgId: number) {
        return link * 65536 + msgId
    }

    private styleFor(link: number, msgId: number) {
        return this.palette.get(this.styleKey(link, msgId))
    }

    /** Decodes one binary frame. Called straight from the WebSocket handler. */
    ingest(frame: ArrayBuffer, now: number): boolean {
        if (frame.byteLength < FRAME_HEADER_BYTES) return false
        const view = new DataView(frame)
        if (view.getUint32(0, true) !== FRAME_MAGIC) return false

        const count = view.getUint32(8, true)
        this.stats.serverDropped = view.getUint32(12, true)
        const serverStamp = view.getFloat64(16, true)
        this.stats.transportLagMs = Math.max(0, Date.now() - serverStamp)
        this.stats.framesReceived++
        this.stats.lastFrameAt = now

        const expected = FRAME_HEADER_BYTES + count * RECORD_BYTES
        const usable = Math.min(count, Math.floor((frame.byteLength - FRAME_HEADER_BYTES) / RECORD_BYTES))
        if (expected !== frame.byteLength) {
            // Short frame: take what is actually there rather than reading past it.
        }

        for (let i = 0; i < usable; i++) {
            this.readRecord(view, FRAME_HEADER_BYTES + i * RECORD_BYTES, now)
        }
        this.stats.samplesReceived += usable
        return usable > 0
    }

    private readRecord(view: DataView, at: number, now: number) {
        const msgId = view.getUint16(at + 4, true)
        const link = view.getUint8(at + 6)
        const kind = view.getUint8(at + 7)
        const trackId = view.getUint32(at + 8, true)
        const flags = view.getUint32(at + 12, true)
        const a = view.getFloat32(at + 16, true)
        const b = view.getFloat32(at + 20, true)
        const c = view.getFloat32(at + 24, true)
        const d = view.getFloat32(at + 28, true)
        const e = view.getFloat32(at + 40, true)
        const f = view.getFloat32(at + 44, true)

        const style = this.styleFor(link, msgId)
        const color = style?.color ?? DEFAULT_COLOR
        const fromOutput = (flags & FLAG_OUTPUT) !== 0
        const emphasis = (flags & FLAG_EMPHASIS) !== 0 || (style?.emphasis ?? false)

        switch (kind) {
            case KIND.POINT:
                this.addPoint(a, b, color, now, emphasis ? 9 : 5,
                    link, msgId, 0, c, d, 0, 0, fromOutput)
                break
            case KIND.TRACK:
                this.addTrackPoint(trackId, a, b, e, f, color, now, fromOutput, link, msgId)
                break
            case KIND.RAY: {
                // c is the ray length, d the bearing in radians -- the same convention
                // the DKM computes with (x = r cos h, y = r sin h).
                const length = c || 2000
                this.rays.push({
                    x1: 0, y1: 0,
                    x2: Math.cos(d) * length, y2: Math.sin(d) * length,
                    color, birth: now, dashed: style?.dashed ?? false,
                    link, msgId, heading: d, fromOutput,
                    lifetime: style?.persistenceMs ?? this.raysLifetimeMs,
                })
                if (this.rays.length > MAX_RAYS) this.rays.splice(0, this.rays.length - MAX_RAYS)
                break
            }
            case KIND.LINE:
                this.lines.push({
                    x1: a, y1: b, x2: e, y2: f, color, birth: now,
                    dashed: style?.dashed ?? false,
                    link, msgId, heading: Math.atan2(f - b, e - a), fromOutput,
                    lifetime: style?.persistenceMs ?? this.raysLifetimeMs,
                })
                if (this.lines.length > MAX_LINES) this.lines.splice(0, this.lines.length - MAX_LINES)
                break
            case KIND.CIRCULAR_AREA:
                this.putArea(this.sectors, a, b, c, d, color, style?.fill ?? 0.12, now, fromOutput, link, msgId)
                break
            case KIND.RECT_AREA:
                this.putArea(this.rects, a, b, c, d, color, style?.fill ?? 0.08, now, fromOutput, link, msgId)
                break
            default:
                break
        }
    }

    private putArea(
        into: Map<string, Area>, a: number, b: number, c: number, d: number,
        color: [number, number, number], fillOpacity: number, now: number, fromOutput: boolean,
        link: number, msgId: number,
    ) {
        // Areas are configuration, not events: re-announcing the same one refreshes
        // it rather than stacking another copy on top.
        const key = `${a.toFixed(3)}|${b.toFixed(3)}|${c.toFixed(3)}|${d.toFixed(3)}`
        const existing = into.get(key)
        if (existing) {
            existing.lastSeen = now
            return
        }
        into.set(key, { key, a, b, c, d, color, fillOpacity, lastSeen: now, fromOutput, link, msgId })
        if (into.size > MAX_AREAS) {
            let oldestKey: string | null = null
            let oldest = Infinity
            for (const [k, area] of into) {
                if (area.lastSeen < oldest) {
                    oldest = area.lastSeen
                    oldestKey = k
                }
            }
            if (oldestKey) into.delete(oldestKey)
        }
    }

    private addPoint(
        x: number, y: number, color: [number, number, number], now: number, size: number,
        link: number, msgId: number, trackId: number,
        distance: number, heading: number, vx: number, vy: number, fromOutput: boolean,
    ) {
        const info = this.pointHead * 3
        this.pointInfo[info] = (link & 0xff) | (fromOutput ? 0x100 : 0)
        this.pointInfo[info + 1] = msgId
        this.pointInfo[info + 2] = trackId
        const geo = this.pointHead * 4
        this.pointGeo[geo] = distance
        this.pointGeo[geo + 1] = heading
        this.pointGeo[geo + 2] = vx
        this.pointGeo[geo + 3] = vy

        const base = this.pointHead * POINT_STRIDE
        this.points[base] = x
        this.points[base + 1] = y
        this.points[base + 2] = color[0]
        this.points[base + 3] = color[1]
        this.points[base + 4] = color[2]
        this.points[base + 5] = now
        this.points[base + 6] = size

        if (this.dirtyCount === 0) this.dirtyFrom = this.pointHead
        this.dirtyCount = Math.min(this.dirtyCount + 1, MAX_POINTS)
        this.pointHead = (this.pointHead + 1) % MAX_POINTS
        this.pointCount = Math.min(this.pointCount + 1, MAX_POINTS)
    }

    private addTrackPoint(
        id: number, x: number, y: number, vx: number, vy: number,
        color: [number, number, number], now: number, fromOutput: boolean,
        link: number, msgId: number,
    ) {
        let track = this.tracks.get(id)
        if (!track) {
            if (this.tracks.size >= MAX_TRACKS) this.pruneTracks(now, 0)
            track = {
                id,
                xs: new Float32Array(TRACK_CAPACITY),
                ys: new Float32Array(TRACK_CAPACITY),
                count: 0, head: 0, color, lastSeen: now,
                lastX: x, lastY: y, vx, vy, fromOutput,
            }
            this.tracks.set(id, track)
        }
        track.xs[track.head] = x
        track.ys[track.head] = y
        track.head = (track.head + 1) % TRACK_CAPACITY
        track.count = Math.min(track.count + 1, TRACK_CAPACITY)
        track.lastSeen = now
        track.lastX = x
        track.lastY = y
        track.vx = vx
        track.vy = vy
        track.color = color
        // A track is still a point on the display; the connecting line is what
        // makes it a track (FR-27).
        this.addPoint(x, y, color, now, 7, link, msgId, id,
            Math.hypot(x, y), Math.atan2(y, x), vx, vy, fromOutput)
    }

    /** Drops tracks nothing has referenced for a while, so a long run stays bounded. */
    pruneTracks(now: number, maxAgeMs = 120_000) {
        for (const [id, track] of this.tracks) {
            if (now - track.lastSeen > maxAgeMs) this.tracks.delete(id)
        }
        if (this.tracks.size > MAX_TRACKS) {
            const sorted = [...this.tracks.values()].sort((a, b) => a.lastSeen - b.lastSeen)
            for (let i = 0; i < sorted.length - MAX_TRACKS; i++) this.tracks.delete(sorted[i].id)
        }
    }

    expire(now: number) {
        // Compacted in place rather than filtered into a new array: segments now
        // carry their own fade times, so there is no single cutoff to test the
        // head against, and this runs every frame.
        compact(this.rays, now)
        compact(this.lines, now)
        this.stats.points = this.pointCount
        this.stats.tracks = this.tracks.size
        this.stats.sectors = this.sectors.size
        this.stats.rects = this.rects.size
        this.stats.rays = this.rays.length
    }

    clear() {
        this.points.fill(0)
        this.pointHead = 0
        this.pointCount = 0
        this.dirtyFrom = 0
        this.dirtyCount = 0
        this.tracks.clear()
        this.sectors.clear()
        this.rects.clear()
        this.rays = []
        this.lines = []
    }


    // ---- picking ---------------------------------------------------------

    /**
     * What is under the cursor, in world coordinates.
     *
     * <p>Marks win over lines, and lines over areas: a detection sitting inside a
     * reporting area is almost always the thing being pointed at, and an area is
     * large enough to be hit anywhere else. Faded marks are skipped, because
     * something invisible should not answer to the cursor.
     */
    pick(x: number, y: number, tolerance: number, now: number): PickResult | null {
        let bestIndex = -1
        let bestDistanceSquared = tolerance * tolerance

        for (let i = 0; i < this.pointCount; i++) {
            const base = i * POINT_STRIDE
            const birth = this.points[base + 5]
            if (now - birth > this.pointLifetimeMs) {
                continue
            }
            const dx = this.points[base] - x
            const dy = this.points[base + 1] - y
            const distanceSquared = dx * dx + dy * dy
            if (distanceSquared <= bestDistanceSquared) {
                bestDistanceSquared = distanceSquared
                bestIndex = i
            }
        }

        if (bestIndex >= 0) {
            const base = bestIndex * POINT_STRIDE
            const info = bestIndex * 3
            const geo = bestIndex * 4
            const trackId = this.pointInfo[info + 2]
            const track = trackId ? this.tracks.get(trackId) : undefined
            return {
                kind: trackId ? 'track' : 'point',
                link: this.pointInfo[info] & 0xff,
                msgId: this.pointInfo[info + 1],
                output: (this.pointInfo[info] & 0x100) !== 0,
                x: this.points[base],
                y: this.points[base + 1],
                distance: this.pointGeo[geo],
                heading: this.pointGeo[geo + 1],
                vx: this.pointGeo[geo + 2],
                vy: this.pointGeo[geo + 3],
                trackId,
                points: track?.count ?? 1,
                ageMs: now - this.points[base + 5],
            }
        }

        for (const list of [this.rays, this.lines]) {
            for (let i = list.length - 1; i >= 0; i--) {
                const segment = list[i]
                if (now - segment.birth > segment.lifetime) {
                    continue
                }
                if (distanceToSegment(x, y, segment.x1, segment.y1, segment.x2, segment.y2) <= tolerance) {
                    return {
                        kind: 'ray',
                        link: segment.link,
                        msgId: segment.msgId,
                        output: segment.fromOutput,
                        heading: segment.heading,
                        length: Math.hypot(segment.x2 - segment.x1, segment.y2 - segment.y1),
                    }
                }
            }
        }

        const range = Math.hypot(x, y)
        const bearing = Math.atan2(y, x)
        for (const area of this.sectors.values()) {
            const r0 = Math.min(area.a, area.b)
            const r1 = Math.max(area.a, area.b)
            const h0 = Math.min(area.c, area.d)
            const h1 = Math.max(area.c, area.d)
            // Sectors are declared in whatever bearing range the DKM used, which need
            // not be the -pi..pi that atan2 returns.
            let probe = bearing
            while (probe < h0 - Math.PI) probe += Math.PI * 2
            while (probe > h1 + Math.PI) probe -= Math.PI * 2
            if (range >= r0 && range <= r1 && probe >= h0 && probe <= h1) {
                return {
                    kind: 'sector', link: area.link, msgId: area.msgId, output: area.fromOutput,
                    r0, r1, h0, h1
                }
            }
        }

        // Reporting areas answer on their border, not across their interior.
        // They are the largest thing drawn -- the current one spans the whole
        // coverage -- so an interior hit would make every empty pixel of the
        // display report "reporting area" and bury everything the operator was
        // actually reaching for. The border is the part that carries the
        // information anyway: where the inclusion test starts and stops.
        for (const area of this.rects.values()) {
            const x0 = Math.min(area.a, area.b)
            const x1 = Math.max(area.a, area.b)
            const y0 = Math.min(area.c, area.d)
            const y1 = Math.max(area.c, area.d)
            const insideOuter = x >= x0 - tolerance && x <= x1 + tolerance
                && y >= y0 - tolerance && y <= y1 + tolerance
            const insideInner = x > x0 + tolerance && x < x1 - tolerance
                && y > y0 + tolerance && y < y1 - tolerance
            if (insideOuter && !insideInner) {
                return {
                    kind: 'rect', link: area.link, msgId: area.msgId, output: area.fromOutput,
                    x0, x1, y0, y1
                }
            }
        }

        return null
    }

    /** Furthest thing currently drawn, for "fit to data". */
    extent(): number {
        let max = 0
        for (const area of this.sectors.values()) max = Math.max(max, Math.abs(area.b))
        for (const area of this.rects.values()) {
            max = Math.max(max, Math.abs(area.a), Math.abs(area.b), Math.abs(area.c), Math.abs(area.d))
        }
        for (let i = 0; i < this.pointCount; i++) {
            const base = i * POINT_STRIDE
            max = Math.max(max, Math.hypot(this.points[base], this.points[base + 1]))
        }
        return max
    }
}

export type { Track, Area, Segment }
export { POINT_STRIDE, MAX_POINTS, TRACK_CAPACITY }
