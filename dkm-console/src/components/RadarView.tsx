import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import { connectViz } from '../api/events'
import type { VizKindName } from '../api/types'
import { Renderer, type Camera, type Palette } from '../gl/Renderer'
import { Scene } from '../gl/Scene'
import { hasTranslation } from '../i18n'
import { useT } from '../i18n/useT'
import { PlanTooltip, type TooltipTarget } from './PlanTooltip'
import { useStore } from '../store/useStore'
import { count, degrees, metres, number } from './format'

/** `#rrggbb` from a CSS variable to the 0..1 triple WebGL wants. */
function readColor(name: string, fallback: [number, number, number]): [number, number, number] {
    const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
    const match = /^#?([0-9a-f]{6})$/i.exec(raw)
    if (!match) return fallback
    const value = parseInt(match[1], 16)
    return [((value >> 16) & 255) / 255, ((value >> 8) & 255) / 255, (value & 255) / 255]
}

/** Perceptual luminance, so the blend mode follows what the eye sees. */
function isDarkGround(): boolean {
    const [r, g, b] = readColor('--c-canvas', [0.027, 0.039, 0.059])
    return 0.2126 * r + 0.7152 * g + 0.0722 * b < 0.5
}

function readPalette(): Palette {
    return {
        canvas: readColor('--c-canvas', [0.027, 0.039, 0.059]),
        grid: readColor('--c-grid', [0.16, 0.24, 0.32]),
        axis: readColor('--c-axis', [0.22, 0.34, 0.44]),
    }
}

/**
 * The visualization surface (FR-33).
 *
 * <p>React owns the frame and the readouts; it does not own the picture. Frames
 * arrive on the WebSocket and go straight into {@link Scene}'s typed arrays,
 * and a {@code requestAnimationFrame} loop draws from those arrays. Nothing
 * about a sample causes a re-render, so the cost of a busy scene is a GPU
 * upload rather than a reconciliation pass -- which is what keeps the picture at
 * frame rate while the message lists are doing their own thing.
 */
export function RadarView() {
    const canvasRef = useRef<HTMLCanvasElement | null>(null)
    const sceneRef = useRef<Scene>(new Scene())
    // Camera lives in a ref: panning at 60 Hz must not re-render the tree.
    const cameraRef = useRef<Camera>({ centerX: 0, centerY: 0, metersPerPixel: 4 })
    const rendererRef = useRef<Renderer | null>(null)

    const t = useT()
    const schema = useStore((s) => s.schema)
    const vizCatalog = useStore((s) => s.vizCatalog)
    const setVizConnected = useStore((s) => s.setVizConnected)
    const theme = useStore((s) => s.theme)

    const [hud, setHud] = useState({
        points: 0, tracks: 0, sectors: 0, rects: 0, rays: 0,
        dropped: 0, lagMs: 0, frameMs: 0, ringStep: 0, scale: 4,
    })
    const [cursor, setCursor] = useState<{ x: number; y: number } | null>(null)
    const [tooltip, setTooltip] = useState<TooltipTarget | null>(null)
    /** Canvas size in CSS pixels, so the tooltip can keep itself on screen. */
    const [canvasSize, setCanvasSize] = useState({ width: 0, height: 0 })
    /** Last cursor position in CSS pixels, sampled by the animation loop rather than by React. */
    const pointerRef = useRef<{ x: number; y: number } | null>(null)
    const colorsRef = useRef<Map<string, string>>(new Map())
    const [glError, setGlError] = useState<string | null>(null)
    const [frozen, setFrozen] = useState(false)
    /**
     * The clock the picture ages by, which is not the wall clock.
     *
     * <p>Marks fade with age, so freezing has to stop time rather than merely
     * stop sampling: a frozen picture whose clock kept running would quietly
     * empty itself while the operator was still reading it. Resuming rolls the
     * offset forward by however long the pause lasted, so every mark carries on
     * from the age it was paused at instead of jumping.
     *
     * <p>A ref rather than state because the frame loop reads it sixty times a
     * second and must never re-render to do so.
     */
    const clockRef = useRef({ offset: 0, stoppedAt: null as number | null })
    const sceneNow = useCallback(() => {
        const clock = clockRef.current
        return clock.stoppedAt ?? performance.now() - clock.offset
    }, [])
    const toggleFrozen = () => {
        const clock = clockRef.current
        if (clock.stoppedAt === null) {
            clock.stoppedAt = performance.now() - clock.offset
        } else {
            clock.offset = performance.now() - clock.stoppedAt
            clock.stoppedAt = null
        }
        setFrozen(clock.stoppedAt !== null)
    }

    // Re-run on a theme change too: the catalog's colours are tuned for a dark
    // ground and have to be deepened for a pale one.
    useEffect(() => {
        if (schema && vizCatalog) {
            const apply = () => sceneRef.current.configure(schema, vizCatalog, isDarkGround())
            const handle = requestAnimationFrame(apply)
            return () => cancelAnimationFrame(handle)
        }
    }, [schema, vizCatalog, theme])

    useEffect(() => {
        const canvas = canvasRef.current
        if (!canvas) return
        let renderer: Renderer
        try {
            renderer = new Renderer(canvas)
        } catch (error) {
            // Whether this machine has a usable WebGL2 context is a fact about the
            // outside world that only exists once the canvas is mounted, so it can
            // only be discovered here.
            setGlError((error as Error).message)
            return
        }
        renderer.setPalette(readPalette())
        rendererRef.current = renderer

        let frame = 0
        let lastHud = 0
        let lastPick = 0
        const scene = sceneRef.current

        const draw = () => {
            // Two clocks on purpose: the picture ages by the scene clock, which stops
            // when frozen, while the throttles below stay on the wall clock so the
            // readouts and the tooltip keep working during a freeze.
            const now = performance.now()
            const sceneTime = sceneNow()
            scene.expire(sceneTime)
            renderer.render(scene, cameraRef.current, sceneTime)
            // Hit testing rides the frame loop rather than the pointer event: one
            // pass per frame at most, and never a React update per mouse move.
            if (now - lastPick > 70) {
                lastPick = now
                const pointer = pointerRef.current
                if (!pointer) {
                    setTooltip((current) => (current ? null : current))
                } else {
                    const camera = cameraRef.current
                    const width = canvas.clientWidth
                    const height = canvas.clientHeight
                    const worldX = camera.centerX + (pointer.x - width / 2) * camera.metersPerPixel
                    const worldY = camera.centerY - (pointer.y - height / 2) * camera.metersPerPixel
                    setCursor({ x: worldX, y: worldY })

                    // Ten pixels of slack, so a mark is easy to hit at any zoom.
                    const hit = scene.pick(worldX, worldY, 10 * camera.metersPerPixel, sceneTime)
                    if (!hit) {
                        setTooltip((current) => (current ? null : current))
                    } else {
                        const typeName = scene.typeName(hit.link, hit.msgId)
                        setTooltip({
                            pick: hit,
                            screenX: pointer.x,
                            screenY: pointer.y,
                            typeName,
                            linkName: scene.linkName(hit.link),
                            color: (typeName && colorsRef.current.get(typeName)) || '#9c948d',
                        })
                    }
                }
            }

            if (now - lastHud > 250) {
                lastHud = now
                setHud({
                    points: scene.stats.points,
                    tracks: scene.stats.tracks,
                    sectors: scene.stats.sectors,
                    rects: scene.stats.rects,
                    rays: scene.stats.rays,
                    dropped: scene.stats.serverDropped,
                    lagMs: scene.stats.transportLagMs,
                    frameMs: renderer.stats.frameMs,
                    ringStep: renderer.stats.ringStepMeters,
                    scale: cameraRef.current.metersPerPixel,
                })
            }
            frame = requestAnimationFrame(draw)
        }
        frame = requestAnimationFrame(draw)

        return () => {
            cancelAnimationFrame(frame)
            renderer.dispose()
            rendererRef.current = null
        }
    }, [sceneNow])

    useEffect(() => {
        const scene = sceneRef.current
        return connectViz(
            (frame) => {
                if (frozen) return
                // Stamped with the scene clock, the same one the animation loop fades
                // by, so a sample never ages against a different reference than the
                // marks already on screen.
                scene.ingest(frame, sceneNow())
            },
            setVizConnected,
        )
    }, [frozen, sceneNow, setVizConnected])

    useEffect(() => {
        const timer = window.setInterval(() => sceneRef.current.pruneTracks(sceneNow()), 15_000)
        return () => window.clearInterval(timer)
    }, [sceneNow])

    // Observing the canvas rather than reading its ref while rendering: the size
    // is owned by layout, and React has to be told about it rather than peeking.
    useEffect(() => {
        const canvas = canvasRef.current
        if (!canvas) return
        const observer = new ResizeObserver(() =>
            setCanvasSize({ width: canvas.clientWidth, height: canvas.clientHeight }))
        observer.observe(canvas)
        return () => observer.disconnect()
    }, [])

    /*
      The picture takes its ground and grid from the same CSS variables the rest
      of the console uses, so it follows the theme instead of being a dark
      rectangle punched into a light page. Re-read on an explicit theme change and
      on a system change, since "system" resolves outside React entirely.
    */
    useEffect(() => {
        const apply = () => rendererRef.current?.setPalette(readPalette())
        // One frame later: the attribute the theme switch just set has to be in the
        // computed style before the variables resolve to the new values.
        const handle = requestAnimationFrame(apply)
        const media = window.matchMedia('(prefers-color-scheme: dark)')
        media.addEventListener('change', apply)
        return () => {
            cancelAnimationFrame(handle)
            media.removeEventListener('change', apply)
        }
    }, [theme])

    const legend = useMemo(() => {
        if (!vizCatalog) return []
        const colors = new Map<string, string>()
        const entries = Object.entries(vizCatalog.mappings)
            .filter(([, mapping]) => mapping.kind !== 'NONE')
            .map(([type, mapping]) => {
                const color = mapping.style?.color ?? '#9c948d'
                colors.set(type, color)
                const key = `viz.label.${type}`
                return {
                    type,
                    kind: mapping.kind as VizKindName,
                    color,
                    // The catalog's label is the fallback: a type the console has no
                    // translation for still gets a readable legend entry.
                    label: hasTranslation(key) ? t(key) : mapping.style?.label ?? type.split('/')[1],
                    kindLabel: t(`viz.kind.${mapping.kind}` as never),
                    note: mapping.note,
                }
            })
        colorsRef.current = colors
        return entries
    }, [vizCatalog, t])

    const toWorld = (event: { clientX: number; clientY: number }) => {
        const canvas = canvasRef.current!
        const rect = canvas.getBoundingClientRect()
        const camera = cameraRef.current
        return {
            x: camera.centerX + (event.clientX - rect.left - rect.width / 2) * camera.metersPerPixel,
            y: camera.centerY - (event.clientY - rect.top - rect.height / 2) * camera.metersPerPixel,
        }
    }

    const onWheel = (event: React.WheelEvent<HTMLCanvasElement>) => {
        const camera = cameraRef.current
        const anchor = toWorld(event)
        const factor = Math.exp(event.deltaY * 0.0015)
        camera.metersPerPixel = Math.min(Math.max(camera.metersPerPixel * factor, 0.02), 20_000)
        const after = toWorld(event)
        // Keep whatever was under the cursor under the cursor.
        camera.centerX += anchor.x - after.x
        camera.centerY += anchor.y - after.y
    }

    const onPointerDown = (event: React.PointerEvent<HTMLCanvasElement>) => {
        const canvas = canvasRef.current!
        canvas.setPointerCapture(event.pointerId)
        let lastX = event.clientX
        let lastY = event.clientY

        const move = (moveEvent: PointerEvent) => {
            const camera = cameraRef.current
            camera.centerX -= (moveEvent.clientX - lastX) * camera.metersPerPixel
            camera.centerY += (moveEvent.clientY - lastY) * camera.metersPerPixel
            lastX = moveEvent.clientX
            lastY = moveEvent.clientY
        }
        const up = () => {
            canvas.removeEventListener('pointermove', move)
            canvas.removeEventListener('pointerup', up)
            canvas.releasePointerCapture(event.pointerId)
        }
        canvas.addEventListener('pointermove', move)
        canvas.addEventListener('pointerup', up)
    }

    const fit = () => {
        const extent = sceneRef.current.extent()
        const canvas = canvasRef.current
        if (!canvas) return
        const camera = cameraRef.current
        camera.centerX = 0
        camera.centerY = 0
        const radius = extent > 0 ? extent * 1.15 : 1000
        camera.metersPerPixel = (2 * radius) / Math.min(canvas.clientWidth, canvas.clientHeight)
    }

    if (glError) {
        return (
            <div className="panel flex-1 items-center justify-center text-danger p-6 text-center">
                <div>
                    <div className="font-semibold mb-1">{t('viz.failedTitle')}</div>
                    <div className="text-ink-400">{glError}</div>
                    <div className="text-ink-400 mt-2">{t('viz.failedHint')}</div>
                </div>
            </div>
        )
    }

    return (
        <div className="panel flex-1 relative min-h-0" data-tour="viz">
            <div className="panel-title">
                <span>{t('viz.title')}</span>
                <span className="flex items-center gap-2 normal-case tracking-normal">
                    <button className="btn text-micro py-0.5" onClick={fit} title={t('viz.fitTitle')}>
                        {t('viz.fit')}
                    </button>
                    <button
                        className={`btn text-micro py-0.5 ${frozen ? 'btn-primary' : ''}`}
                        onClick={toggleFrozen}
                        title={t('viz.freezeTitle')}
                    >
                        {frozen ? t('viz.frozen') : t('viz.live')}
                    </button>
                    <button
                        className="btn text-micro py-0.5"
                        onClick={() => sceneRef.current.clear()}
                        title={t('viz.clearTitle')}
                    >
                        {t('viz.clear')}
                    </button>
                </span>
            </div>

            <canvas
                ref={canvasRef}
                className="flex-1 w-full min-h-0 block cursor-crosshair touch-none"
                onWheel={onWheel}
                onPointerDown={onPointerDown}
                onPointerMove={(e) => {
                    const canvas = canvasRef.current
                    if (!canvas) return
                    const rect = canvas.getBoundingClientRect()
                    pointerRef.current = { x: e.clientX - rect.left, y: e.clientY - rect.top }
                }}
                onPointerLeave={() => {
                    pointerRef.current = null
                    setCursor(null)
                    setTooltip(null)
                }}
                onDoubleClick={fit}
            />

            {/* Labels live in the DOM rather than the GL context: text is the one
          thing a canvas is worse at than the browser already is. */}
            <div className="absolute left-3 top-11 pointer-events-none text-micro leading-relaxed">
                <div className="text-ink-400">
                    {t('viz.ring', { range: metres(hud.ringStep) })}
                    {' · '}{t('viz.scale', { value: number(hud.scale, 2) })}
                </div>
                <div className="text-ink-500">{t('viz.convention')}</div>
            </div>

            <div className="absolute right-3 top-11 text-micro text-right leading-relaxed pointer-events-none">
                <div className="text-ink-400">
                    {t('viz.marks', { marks: count(hud.points), tracks: count(hud.tracks) })}
                </div>
                <div className="text-ink-400">
                    {t('viz.areas', { gate: hud.sectors, reporting: hud.rects, rays: hud.rays })}
                </div>
                <div className={hud.lagMs > 100 ? 'text-caution' : 'text-ink-500'}>
                    {t('viz.latency', { ms: count(Math.round(hud.lagMs)), frame: number(hud.frameMs, 1) })}
                </div>
                {hud.dropped > 0 && (
                    <div className="text-caution">
                        {t('viz.droppedUpstream', { count: count(hud.dropped) })}
                    </div>
                )}
            </div>

            {cursor && (
                <div className="absolute left-3 bottom-3 text-micro text-ink-300 pointer-events-none">
                    {metres(cursor.x)}, {metres(cursor.y)}
                    {' · '}r {metres(Math.hypot(cursor.x, cursor.y))}
                    {' · '}h {degrees(Math.atan2(cursor.y, cursor.x))}
                </div>
            )}

            {tooltip && (
                <PlanTooltip target={tooltip} bounds={canvasSize} />
            )}

            <div className="absolute right-3 bottom-3 flex flex-col items-end gap-0.5 text-mini">
                {legend.map((entry) => (
                    <span key={entry.type} className="flex items-center gap-1.5 text-ink-300" title={entry.note ?? entry.type}>
                        <span className="w-2 h-2 rounded-sm" style={{ background: entry.color }} />
                        {entry.label}
                        <span className="text-ink-500">{entry.kindLabel}</span>
                    </span>
                ))}
            </div>
        </div>
    )
}
