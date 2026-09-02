import { POINT_STRIDE, type Scene } from './Scene'

/**
 * WebGL2 renderer for the plan-position picture.
 *
 * <p>Three buffers, three draw calls, one animation frame. Area fills are
 * alpha-blended so overlapping regions read as overlapping; everything else is
 * additively blended, which is what makes a dark display look like an
 * instrument rather than a chart.
 *
 * <p>The point ring is uploaded by dirty range rather than wholesale. At a full
 * ring that is the difference between a megabyte per frame and a few kilobytes,
 * and it is the reason a busy scene costs the same as a quiet one.
 */

export interface Camera {
    centerX: number
    centerY: number
    /** Metres per CSS pixel. Larger means zoomed out. */
    metersPerPixel: number
}

const FLAT_VERT = `#version 300 es
precision highp float;
in vec2 aPos;
in vec4 aColor;
uniform vec2 uCenter;
uniform vec2 uScale;
out vec4 vColor;
void main() {
  vColor = aColor;
  gl_Position = vec4((aPos - uCenter) * uScale, 0.0, 1.0);
}`

const FLAT_FRAG = `#version 300 es
precision highp float;
in vec4 vColor;
out vec4 outColor;
void main() { outColor = vColor; }`

const POINT_VERT = `#version 300 es
precision highp float;
in vec2 aPos;
in vec3 aColor;
in float aBirth;
in float aSize;
uniform vec2 uCenter;
uniform vec2 uScale;
uniform float uNow;
uniform float uLifetime;
uniform float uDpr;
out vec4 vColor;
void main() {
  float age = clamp((uNow - aBirth) / uLifetime, 0.0, 1.0);
  float fade = 1.0 - age;
  // Squared falloff: a mark stays legible for most of its life, then goes
  // quickly, so what is on screen is what just happened.
  vColor = vec4(aColor, fade * fade * 0.9 + 0.05);
  gl_PointSize = aSize * uDpr * (0.55 + 0.45 * fade);
  gl_Position = vec4((aPos - uCenter) * uScale, 0.0, 1.0);
}`

const POINT_FRAG = `#version 300 es
precision highp float;
in vec4 vColor;
out vec4 outColor;
void main() {
  vec2 offset = gl_PointCoord * 2.0 - 1.0;
  float radius = dot(offset, offset);
  if (radius > 1.0) discard;
  float core = smoothstep(1.0, 0.15, radius);
  outColor = vec4(vColor.rgb, vColor.a * core);
}`

/** Grows geometrically, so a frame never allocates once the scene is warm. */
class VertexList {
    data: Float32Array
    length = 0

    constructor(initial: number) {
        this.data = new Float32Array(initial)
    }

    reset() {
        this.length = 0
    }

    private ensure(extra: number) {
        if (this.length + extra <= this.data.length) return
        let capacity = this.data.length || 1024
        while (capacity < this.length + extra) capacity *= 2
        const grown = new Float32Array(capacity)
        grown.set(this.data.subarray(0, this.length))
        this.data = grown
    }

    vertex(x: number, y: number, r: number, g: number, b: number, a: number) {
        this.ensure(6)
        const at = this.length
        this.data[at] = x
        this.data[at + 1] = y
        this.data[at + 2] = r
        this.data[at + 3] = g
        this.data[at + 4] = b
        this.data[at + 5] = a
        this.length = at + 6
    }

    line(x1: number, y1: number, x2: number, y2: number,
        c: readonly [number, number, number], a: number) {
        this.vertex(x1, y1, c[0], c[1], c[2], a)
        this.vertex(x2, y2, c[0], c[1], c[2], a)
    }

    triangle(x1: number, y1: number, x2: number, y2: number, x3: number, y3: number,
        c: readonly [number, number, number], a: number) {
        this.vertex(x1, y1, c[0], c[1], c[2], a)
        this.vertex(x2, y2, c[0], c[1], c[2], a)
        this.vertex(x3, y3, c[0], c[1], c[2], a)
    }

    get vertexCount() {
        return this.length / 6
    }
}

/**
 * The ground and the grid come from the same CSS variables the rest of the
 * console uses, so the picture follows the theme rather than being a dark
 * rectangle punched into a light page.
 */
export interface Palette {
    canvas: [number, number, number]
    grid: [number, number, number]
    axis: [number, number, number]
}

/**
 * Adding light to a dark ground is what makes a plan display look like an
 * instrument. Adding light to a *pale* ground makes marks disappear into it, so
 * the light theme composites normally instead. The ground's own luminance is
 * the only thing that decides.
 */
function isDarkGround(palette: Palette): boolean {
    const [r, g, b] = palette.canvas
    return 0.2126 * r + 0.7152 * g + 0.0722 * b < 0.5
}

const DARK_PALETTE: Palette = {
    canvas: [0.027, 0.039, 0.059],
    grid: [0.16, 0.24, 0.32],
    axis: [0.22, 0.34, 0.44],
}

export interface RenderStats {
    lineVertices: number
    triangleVertices: number
    points: number
    ringStepMeters: number
    frameMs: number
}

export class Renderer {
    private gl: WebGL2RenderingContext
    private flatProgram: WebGLProgram
    private pointProgram: WebGLProgram
    private lineBuffer: WebGLBuffer
    private triBuffer: WebGLBuffer
    private pointBuffer: WebGLBuffer
    private lineVao: WebGLVertexArrayObject
    private triVao: WebGLVertexArrayObject
    private pointVao: WebGLVertexArrayObject

    private readonly lines = new VertexList(1 << 16)
    private readonly triangles = new VertexList(1 << 16)
    private pointCapacityBytes = 0

    private uFlatCenter: WebGLUniformLocation | null
    private uFlatScale: WebGLUniformLocation | null
    private uPointCenter: WebGLUniformLocation | null
    private uPointScale: WebGLUniformLocation | null
    private uPointNow: WebGLUniformLocation | null
    private uPointLifetime: WebGLUniformLocation | null
    private uPointDpr: WebGLUniformLocation | null

    stats: RenderStats = {
        lineVertices: 0, triangleVertices: 0, points: 0, ringStepMeters: 0, frameMs: 0,
    }

    private palette: Palette = DARK_PALETTE

    setPalette(palette: Palette) {
        this.palette = palette
    }

    private readonly canvas: HTMLCanvasElement

    constructor(canvas: HTMLCanvasElement) {
        this.canvas = canvas
        const gl = canvas.getContext('webgl2', {
            alpha: false,
            antialias: true,
            depth: false,
            premultipliedAlpha: false,
            powerPreference: 'high-performance',
        })
        if (!gl) throw new Error('WebGL2 is not available in this browser')
        this.gl = gl

        this.flatProgram = this.link(FLAT_VERT, FLAT_FRAG)
        this.pointProgram = this.link(POINT_VERT, POINT_FRAG)

        this.uFlatCenter = gl.getUniformLocation(this.flatProgram, 'uCenter')
        this.uFlatScale = gl.getUniformLocation(this.flatProgram, 'uScale')
        this.uPointCenter = gl.getUniformLocation(this.pointProgram, 'uCenter')
        this.uPointScale = gl.getUniformLocation(this.pointProgram, 'uScale')
        this.uPointNow = gl.getUniformLocation(this.pointProgram, 'uNow')
        this.uPointLifetime = gl.getUniformLocation(this.pointProgram, 'uLifetime')
        this.uPointDpr = gl.getUniformLocation(this.pointProgram, 'uDpr')

        this.lineBuffer = gl.createBuffer()!
        this.triBuffer = gl.createBuffer()!
        this.pointBuffer = gl.createBuffer()!
        this.lineVao = this.flatVao(this.lineBuffer)
        this.triVao = this.flatVao(this.triBuffer)
        this.pointVao = this.makePointVao()

        gl.disable(gl.DEPTH_TEST)
        gl.enable(gl.BLEND)
    }

    private link(vertexSource: string, fragmentSource: string): WebGLProgram {
        const gl = this.gl
        const program = gl.createProgram()!
        for (const [type, source] of [
            [gl.VERTEX_SHADER, vertexSource] as const,
            [gl.FRAGMENT_SHADER, fragmentSource] as const,
        ]) {
            const shader = gl.createShader(type)!
            gl.shaderSource(shader, source)
            gl.compileShader(shader)
            if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
                throw new Error(`shader failed to compile: ${gl.getShaderInfoLog(shader)}`)
            }
            gl.attachShader(program, shader)
            gl.deleteShader(shader)
        }
        gl.linkProgram(program)
        if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
            throw new Error(`program failed to link: ${gl.getProgramInfoLog(program)}`)
        }
        return program
    }

    private flatVao(buffer: WebGLBuffer): WebGLVertexArrayObject {
        const gl = this.gl
        const vao = gl.createVertexArray()!
        gl.bindVertexArray(vao)
        gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
        const stride = 6 * 4
        const position = gl.getAttribLocation(this.flatProgram, 'aPos')
        const color = gl.getAttribLocation(this.flatProgram, 'aColor')
        gl.enableVertexAttribArray(position)
        gl.vertexAttribPointer(position, 2, gl.FLOAT, false, stride, 0)
        gl.enableVertexAttribArray(color)
        gl.vertexAttribPointer(color, 4, gl.FLOAT, false, stride, 2 * 4)
        gl.bindVertexArray(null)
        return vao
    }

    private makePointVao(): WebGLVertexArrayObject {
        const gl = this.gl
        const vao = gl.createVertexArray()!
        gl.bindVertexArray(vao)
        gl.bindBuffer(gl.ARRAY_BUFFER, this.pointBuffer)
        const stride = POINT_STRIDE * 4
        const position = gl.getAttribLocation(this.pointProgram, 'aPos')
        const color = gl.getAttribLocation(this.pointProgram, 'aColor')
        const birth = gl.getAttribLocation(this.pointProgram, 'aBirth')
        const size = gl.getAttribLocation(this.pointProgram, 'aSize')
        gl.enableVertexAttribArray(position)
        gl.vertexAttribPointer(position, 2, gl.FLOAT, false, stride, 0)
        gl.enableVertexAttribArray(color)
        gl.vertexAttribPointer(color, 3, gl.FLOAT, false, stride, 2 * 4)
        gl.enableVertexAttribArray(birth)
        gl.vertexAttribPointer(birth, 1, gl.FLOAT, false, stride, 5 * 4)
        gl.enableVertexAttribArray(size)
        gl.vertexAttribPointer(size, 1, gl.FLOAT, false, stride, 6 * 4)
        gl.bindVertexArray(null)
        return vao
    }

    /** Returns the CSS size, after matching the drawing buffer to the device pixel ratio. */
    resize(): { width: number; height: number; dpr: number } {
        const dpr = Math.min(window.devicePixelRatio || 1, 2)
        const width = this.canvas.clientWidth || 1
        const height = this.canvas.clientHeight || 1
        const targetWidth = Math.round(width * dpr)
        const targetHeight = Math.round(height * dpr)
        if (this.canvas.width !== targetWidth || this.canvas.height !== targetHeight) {
            this.canvas.width = targetWidth
            this.canvas.height = targetHeight
        }
        this.gl.viewport(0, 0, targetWidth, targetHeight)
        return { width, height, dpr }
    }

    render(scene: Scene, camera: Camera, now: number) {
        const started = performance.now()
        const gl = this.gl
        const { width, height, dpr } = this.resize()

        const ground = this.palette.canvas
        gl.clearColor(ground[0], ground[1], ground[2], 1)
        gl.clear(gl.COLOR_BUFFER_BIT)

        const scaleX = 2 / (width * camera.metersPerPixel)
        const scaleY = 2 / (height * camera.metersPerPixel)

        this.lines.reset()
        this.triangles.reset()

        // Far enough to leave the viewport from the centre at this zoom, with the
        // pan offset allowed for, so an unbounded ray always runs off the edge.
        scene.viewRadius = Math.hypot(width, height) * camera.metersPerPixel
            + Math.hypot(camera.centerX, camera.centerY)

        const ringStep = this.buildGrid(camera, width, height)
        this.buildRects(scene, now)
        this.buildSectors(scene, now)
        this.buildTracks(scene)
        this.buildSegments(scene, now)

        // Area fills first, alpha-blended so overlaps read as overlaps.
        gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA)
        gl.useProgram(this.flatProgram)
        gl.uniform2f(this.uFlatCenter, camera.centerX, camera.centerY)
        gl.uniform2f(this.uFlatScale, scaleX, scaleY)
        if (this.triangles.vertexCount > 0) {
            gl.bindVertexArray(this.triVao)
            gl.bindBuffer(gl.ARRAY_BUFFER, this.triBuffer)
            gl.bufferData(gl.ARRAY_BUFFER, this.triangles.data.subarray(0, this.triangles.length), gl.DYNAMIC_DRAW)
            gl.drawArrays(gl.TRIANGLES, 0, this.triangles.vertexCount)
        }

        // On a dark ground marks add light; on a pale one they composite normally,
        // or they would wash straight out.
        gl.blendFunc(gl.SRC_ALPHA, isDarkGround(this.palette) ? gl.ONE : gl.ONE_MINUS_SRC_ALPHA)
        if (this.lines.vertexCount > 0) {
            gl.bindVertexArray(this.lineVao)
            gl.bindBuffer(gl.ARRAY_BUFFER, this.lineBuffer)
            gl.bufferData(gl.ARRAY_BUFFER, this.lines.data.subarray(0, this.lines.length), gl.DYNAMIC_DRAW)
            gl.drawArrays(gl.LINES, 0, this.lines.vertexCount)
        }

        this.drawPoints(scene, camera, now, scaleX, scaleY, dpr)

        gl.bindVertexArray(null)
        this.stats = {
            lineVertices: this.lines.vertexCount,
            triangleVertices: this.triangles.vertexCount,
            points: scene.pointCount,
            ringStepMeters: ringStep,
            frameMs: performance.now() - started,
        }
    }

    private drawPoints(scene: Scene, camera: Camera, now: number,
        scaleX: number, scaleY: number, dpr: number) {
        const gl = this.gl
        if (scene.pointCount === 0) return
        const bytes = scene.points.byteLength

        gl.bindVertexArray(this.pointVao)
        gl.bindBuffer(gl.ARRAY_BUFFER, this.pointBuffer)
        if (this.pointCapacityBytes !== bytes) {
            gl.bufferData(gl.ARRAY_BUFFER, scene.points, gl.DYNAMIC_DRAW)
            this.pointCapacityBytes = bytes
        } else if (scene.dirtyCount > 0) {
            // The ring is written sequentially, so the dirty region is one interval --
            // or two when it wrapped. Either way, only what changed goes to the GPU.
            const capacity = scene.points.length / POINT_STRIDE
            const from = scene.dirtyFrom
            const count = scene.dirtyCount
            const first = Math.min(count, capacity - from)
            gl.bufferSubData(gl.ARRAY_BUFFER, from * POINT_STRIDE * 4,
                scene.points.subarray(from * POINT_STRIDE, (from + first) * POINT_STRIDE))
            if (count > first) {
                gl.bufferSubData(gl.ARRAY_BUFFER, 0, scene.points.subarray(0, (count - first) * POINT_STRIDE))
            }
        }
        scene.dirtyCount = 0

        gl.useProgram(this.pointProgram)
        gl.uniform2f(this.uPointCenter, camera.centerX, camera.centerY)
        gl.uniform2f(this.uPointScale, scaleX, scaleY)
        gl.uniform1f(this.uPointNow, now)
        gl.uniform1f(this.uPointLifetime, scene.pointLifetimeMs)
        gl.uniform1f(this.uPointDpr, dpr)
        gl.drawArrays(gl.POINTS, 0, scene.pointCount)
    }

    /** Range rings and bearing spokes, at a step chosen from the current zoom. */
    private buildGrid(camera: Camera, width: number, height: number): number {
        const halfDiagonal = Math.hypot(width, height) * camera.metersPerPixel * 0.5
        const step = niceStep(halfDiagonal / 4)
        const maxRadius = halfDiagonal + Math.hypot(camera.centerX, camera.centerY)
        const rings = Math.min(Math.ceil(maxRadius / step), 40)

        for (let i = 1; i <= rings; i++) {
            const radius = i * step
            const segments = 128
            for (let s = 0; s < segments; s++) {
                const a0 = (s / segments) * Math.PI * 2
                const a1 = ((s + 1) / segments) * Math.PI * 2
                this.lines.line(
                    Math.cos(a0) * radius, Math.sin(a0) * radius,
                    Math.cos(a1) * radius, Math.sin(a1) * radius,
                    this.palette.grid, i % 5 === 0 ? 0.9 : 0.5,
                )
            }
        }
        // Bearing spokes every 30 degrees, plus emphasised axes.
        const spokeLength = rings * step
        for (let deg = 0; deg < 360; deg += 30) {
            const angle = (deg * Math.PI) / 180
            const axis = deg % 90 === 0
            this.lines.line(0, 0, Math.cos(angle) * spokeLength, Math.sin(angle) * spokeLength,
                axis ? this.palette.axis : this.palette.grid, axis ? 0.8 : 0.35)
        }
        return step
    }

    private buildRects(scene: Scene, now: number) {
        for (const area of scene.rects.values()) {
            if (scene.isHidden(area.link, area.msgId)) continue
            const x0 = Math.min(area.a, area.b)
            const x1 = Math.max(area.a, area.b)
            const y0 = Math.min(area.c, area.d)
            const y1 = Math.max(area.c, area.d)
            const fresh = Math.max(0.35, 1 - (now - area.lastSeen) / 20000)
            this.triangles.triangle(x0, y0, x1, y0, x1, y1, area.color, area.fillOpacity * fresh)
            this.triangles.triangle(x0, y0, x1, y1, x0, y1, area.color, area.fillOpacity * fresh)
            this.lines.line(x0, y0, x1, y0, area.color, 0.75)
            this.lines.line(x1, y0, x1, y1, area.color, 0.75)
            this.lines.line(x1, y1, x0, y1, area.color, 0.75)
            this.lines.line(x0, y1, x0, y0, area.color, 0.75)
        }
    }

    /**
     * A gate area is polar: a distance band crossed with a heading band. Drawing
     * it as a rectangle -- which is what treating its four numbers as x/y bounds
     * would do -- would put the exclusion region somewhere the DKM never applied
     * it (FR-26).
     */
    private buildSectors(scene: Scene, now: number) {
        for (const area of scene.sectors.values()) {
            if (scene.isHidden(area.link, area.msgId)) continue
            const r0 = Math.min(area.a, area.b)
            const r1 = Math.max(area.a, area.b)
            const h0 = Math.min(area.c, area.d)
            const h1 = Math.max(area.c, area.d)
            const span = Math.max(h1 - h0, 1e-4)
            const steps = Math.max(6, Math.min(160, Math.ceil(span / 0.02)))
            const fresh = Math.max(0.35, 1 - (now - area.lastSeen) / 20000)

            for (let i = 0; i < steps; i++) {
                const a0 = h0 + (span * i) / steps
                const a1 = h0 + (span * (i + 1)) / steps
                const c0 = Math.cos(a0)
                const s0 = Math.sin(a0)
                const c1 = Math.cos(a1)
                const s1 = Math.sin(a1)
                this.triangles.triangle(
                    r0 * c0, r0 * s0, r1 * c0, r1 * s0, r1 * c1, r1 * s1,
                    area.color, area.fillOpacity * fresh)
                this.triangles.triangle(
                    r0 * c0, r0 * s0, r1 * c1, r1 * s1, r0 * c1, r0 * s1,
                    area.color, area.fillOpacity * fresh)
                this.lines.line(r1 * c0, r1 * s0, r1 * c1, r1 * s1, area.color, 0.8)
                this.lines.line(r0 * c0, r0 * s0, r0 * c1, r0 * s1, area.color, 0.5)
            }
            this.lines.line(r0 * Math.cos(h0), r0 * Math.sin(h0), r1 * Math.cos(h0), r1 * Math.sin(h0),
                area.color, 0.8)
            this.lines.line(r0 * Math.cos(h1), r0 * Math.sin(h1), r1 * Math.cos(h1), r1 * Math.sin(h1),
                area.color, 0.8)
        }
    }

    /** FR-27: observations sharing a correlation id are one connected track. */
    private buildTracks(scene: Scene) {
        for (const track of scene.tracks.values()) {
            if (track.count < 2) continue
            if (scene.isHidden(track.link, track.msgId)) continue
            const capacity = track.xs.length
            const oldest = (track.head - track.count + capacity) % capacity
            let previousX = track.xs[oldest]
            let previousY = track.ys[oldest]
            for (let i = 1; i < track.count; i++) {
                const at = (oldest + i) % capacity
                const x = track.xs[at]
                const y = track.ys[at]
                // The tail fades toward the past, so the direction of travel is legible
                // without needing an arrowhead.
                const alpha = 0.12 + 0.78 * (i / track.count)
                this.lines.line(previousX, previousY, x, y, track.color, alpha)
                previousX = x
                previousY = y
            }
            // Velocity vector: two seconds ahead at the reported rate.
            if (track.vx !== 0 || track.vy !== 0) {
                this.lines.line(track.lastX, track.lastY,
                    track.lastX + track.vx * 2, track.lastY + track.vy * 2, track.color, 0.6)
            }
        }
    }

    private buildSegments(scene: Scene, now: number) {
        for (const list of [scene.rays, scene.lines]) {
            for (const segment of list) {
                if (scene.isHidden(segment.link, segment.msgId)) continue
                const age = (now - segment.birth) / segment.lifetime
                if (age >= 1) continue
                const alpha = (1 - age) * 0.8
                const endX = segment.unbounded
                    ? Math.cos(segment.heading) * scene.viewRadius : segment.x2
                const endY = segment.unbounded
                    ? Math.sin(segment.heading) * scene.viewRadius : segment.y2
                if (segment.dashed) {
                    const dashes = 24
                    for (let i = 0; i < dashes; i += 2) {
                        const t0 = i / dashes
                        const t1 = (i + 1) / dashes
                        this.lines.line(
                            segment.x1 + (endX - segment.x1) * t0,
                            segment.y1 + (endY - segment.y1) * t0,
                            segment.x1 + (endX - segment.x1) * t1,
                            segment.y1 + (endY - segment.y1) * t1,
                            segment.color, alpha)
                    }
                } else {
                    this.lines.line(segment.x1, segment.y1, endX, endY, segment.color, alpha)
                }
            }
        }
    }

    dispose() {
        const gl = this.gl
        gl.deleteBuffer(this.lineBuffer)
        gl.deleteBuffer(this.triBuffer)
        gl.deleteBuffer(this.pointBuffer)
        gl.deleteVertexArray(this.lineVao)
        gl.deleteVertexArray(this.triVao)
        gl.deleteVertexArray(this.pointVao)
        gl.deleteProgram(this.flatProgram)
        gl.deleteProgram(this.pointProgram)
    }
}

/** 1 / 2 / 5 x 10^n, so ring labels are always round numbers. */
function niceStep(target: number): number {
    if (!Number.isFinite(target) || target <= 0) return 1
    const magnitude = 10 ** Math.floor(Math.log10(target))
    const normalised = target / magnitude
    const step = normalised >= 5 ? 5 : normalised >= 2 ? 2 : 1
    return step * magnitude
}
