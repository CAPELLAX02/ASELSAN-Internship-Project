import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'

import type { TranslationKey } from '../i18n'
import { useT } from '../i18n/useT'
import { useStore } from '../store/useStore'

/**
 * The guided introduction, shown once the DKM is actually attached.
 *
 * <p>That trigger is deliberate. An introduction on first page load would
 * describe a tool that cannot do anything yet: nothing connected, nothing to
 * send, nothing to look at. The moment the DKM connects is the moment every
 * panel it describes becomes real.
 *
 * <p>Skippable from the first frame, dismissed for good once finished, and
 * available again from the ? button. Highlighting is done by dimming around the
 * anchor rather than by cutting a hole through it: four plain rectangles
 * composite reliably everywhere, where a mask or clip-path is one browser quirk
 * away from hiding the thing it was meant to reveal.
 */

interface Step {
    /** `data-tour` value of the element to highlight; absent means centre screen. */
    anchor?: string
    title: TranslationKey
    body: TranslationKey
}

const STEPS: Step[] = [
    { title: 'tour.welcome.title', body: 'tour.welcome.body' },
    { anchor: 'links', title: 'tour.links.title', body: 'tour.links.body' },
    { anchor: 'transport', title: 'tour.transport.title', body: 'tour.transport.body' },
    { anchor: 'list', title: 'tour.list.title', body: 'tour.list.body' },
    { anchor: 'inspector', title: 'tour.inspector.title', body: 'tour.inspector.body' },
    { anchor: 'viz', title: 'tour.viz.title', body: 'tour.viz.body' },
    { anchor: 'log', title: 'tour.log.title', body: 'tour.log.body' },
]

interface Rect { top: number; left: number; width: number; height: number }

const PAD = 6
const CARD_WIDTH = 400
const GAP = 16
/** Never closer than this to a viewport edge. */
const MARGIN = 16
const EASE = 'cubic-bezier(0.22, 0.61, 0.36, 1)'
const DURATION = 260

export function Tour() {
    const t = useT()
    const lang = useStore((s) => s.lang)
    const open = useStore((s) => s.tourOpen)
    const closeTour = useStore((s) => s.closeTour)

    const [index, setIndex] = useState(0)
    const [rect, setRect] = useState<Rect | null>(null)
    const [card, setCard] = useState({ width: CARD_WIDTH, height: 220 })
    const [viewport, setViewport] = useState({ width: 0, height: 0 })
    const cardRef = useRef<HTMLDivElement | null>(null)

    const step = STEPS[index]
    const last = index === STEPS.length - 1

    const finish = useCallback(() => {
        closeTour(true)
        setIndex(0)
    }, [closeTour])

    const measure = useCallback(() => {
        setViewport({ width: window.innerWidth, height: window.innerHeight })
        if (!step?.anchor) {
            setRect(null)
            return
        }
        const element = document.querySelector(`[data-tour="${step.anchor}"]`)
        if (!element) {
            setRect(null)
            return
        }
        const box = element.getBoundingClientRect()
        setRect({
            top: box.top - PAD,
            left: box.left - PAD,
            width: box.width + PAD * 2,
            height: box.height + PAD * 2,
        })
    }, [step])

    // Measuring where an element actually landed is reading an external system:
    // the geometry only exists after layout and changes for reasons React never
    // sees. The card's own size is measured the same way, because the text is
    // translated and its height is not knowable in advance.
    useLayoutEffect(() => {
        if (!open) return
        measure()
        if (cardRef.current) {
            setCard({
                width: cardRef.current.offsetWidth,
                height: cardRef.current.offsetHeight,
            })
        }
        window.addEventListener('resize', measure)
        window.addEventListener('scroll', measure, true)
        return () => {
            window.removeEventListener('resize', measure)
            window.removeEventListener('scroll', measure, true)
        }
    }, [open, measure, lang, index])

    useEffect(() => {
        if (open) {
            cardRef.current?.focus()
        }
    }, [open, index])

    useEffect(() => {
        if (!open) return
        const onKey = (event: KeyboardEvent) => {
            if (event.key === 'Escape') {
                finish()
            } else if (event.key === 'ArrowRight' || event.key === 'Enter') {
                setIndex((i) => (i === STEPS.length - 1 ? (finish(), 0) : i + 1))
            } else if (event.key === 'ArrowLeft') {
                setIndex((i) => Math.max(0, i - 1))
            }
        }
        window.addEventListener('keydown', onKey)
        return () => window.removeEventListener('keydown', onKey)
    }, [open, finish])

    if (!open || !step) {
        return null
    }

    const vw = viewport.width || window.innerWidth
    const vh = viewport.height || window.innerHeight
    const position = place(rect, card, vw, vh)

    return (
        <div
            className="fixed inset-0 z-50"
            role="dialog"
            aria-modal="true"
            aria-label={t('settings.help')}
        >
            {rect
                ? <Spotlight rect={rect} width={vw} height={vh} />
                : <div className="absolute inset-0 bg-ink-950/80" onClick={finish} />}

            {rect && (
                <div
                    className="absolute rounded-md pointer-events-none border-2 border-signal"
                    style={{
                        top: rect.top, left: rect.left, width: rect.width, height: rect.height,
                        boxShadow: '0 0 0 1px color-mix(in srgb, var(--c-signal) 35%, transparent),'
                            + ' 0 0 34px -6px var(--c-signal)',
                        transition: `all ${DURATION}ms ${EASE}`,
                    }}
                />
            )}

            <div
                ref={cardRef}
                tabIndex={-1}
                className="absolute rounded-lg border border-ink-600 bg-ink-900 shadow-2xl p-4
                   focus:outline-none"
                style={{
                    width: Math.min(CARD_WIDTH, vw - MARGIN * 2),
                    top: position.top,
                    left: position.left,
                    transition: `top ${DURATION}ms ${EASE}, left ${DURATION}ms ${EASE}`,
                }}
            >
                <div className="flex items-center justify-between gap-3 mb-2.5">
                    <span className="text-mini uppercase tracking-[0.12em] font-semibold text-signal num">
                        {t('tour.step', { current: index + 1, total: STEPS.length })}
                    </span>
                    <div className="flex gap-1" aria-hidden="true">
                        {STEPS.map((_, i) => (
                            <span
                                key={i}
                                className={`h-1 rounded-full ${i === index ? 'w-5 bg-signal' : 'w-1.5 bg-ink-600'}`}
                                style={{ transition: `width ${DURATION}ms ${EASE}, background-color ${DURATION}ms` }}
                            />
                        ))}
                    </div>
                </div>

                {/* Keyed on the step so the text cross-fades instead of snapping. */}
                <div key={index} className="tour-step">
                    <h2 className="text-ink-100 text-lead font-semibold mb-1.5 text-balance">
                        {t(step.title)}
                    </h2>
                    <p className="text-ink-300 leading-relaxed mb-4">{t(step.body)}</p>
                </div>

                <div className="flex items-center gap-2">
                    <button className="btn text-ink-400" onClick={finish}>{t('tour.skip')}</button>
                    <div className="flex-1" />
                    <button
                        className="btn"
                        disabled={index === 0}
                        onClick={() => setIndex((i) => Math.max(0, i - 1))}
                    >
                        {t('tour.back')}
                    </button>
                    <button
                        className="btn btn-primary"
                        onClick={() => (last ? finish() : setIndex((i) => i + 1))}
                    >
                        {last ? t('tour.done') : t('tour.next')}
                    </button>
                </div>
            </div>
        </div>
    )
}

/**
 * Puts the card next to the anchor and fully on screen.
 *
 * <p>Tried in order: below, above, to one side, and only then over the anchor
 * itself. A tall panel leaves no room above or below it but plenty beside it,
 * and a card that covers the thing it is describing is the one placement worth
 * avoiding. Everything is clamped into the viewport at the end, because an
 * introduction the reader has to scroll to find is worse than none.
 */
function place(
    rect: Rect | null,
    card: { width: number; height: number },
    vw: number,
    vh: number,
): { top: number; left: number } {
    const width = Math.min(card.width || CARD_WIDTH, vw - MARGIN * 2)
    const height = card.height || 220
    const fitTop = (value: number) => clamp(value, MARGIN, Math.max(MARGIN, vh - height - MARGIN))
    const fitLeft = (value: number) => clamp(value, MARGIN, Math.max(MARGIN, vw - width - MARGIN))

    if (!rect) {
        return { top: Math.max(MARGIN, (vh - height) / 2), left: Math.max(MARGIN, (vw - width) / 2) }
    }

    const below = rect.top + rect.height + GAP
    if (below + height + MARGIN <= vh) {
        return { top: fitTop(below), left: fitLeft(rect.left) }
    }

    const above = rect.top - GAP - height
    if (above >= MARGIN) {
        return { top: fitTop(above), left: fitLeft(rect.left) }
    }

    // Beside it: centre the card on the anchor vertically and put it in whichever
    // margin is wide enough.
    const beside = fitTop(rect.top + rect.height / 2 - height / 2)
    const rightOf = rect.left + rect.width + GAP
    if (rightOf + width + MARGIN <= vw) {
        return { top: beside, left: rightOf }
    }
    const leftOf = rect.left - GAP - width
    if (leftOf >= MARGIN) {
        return { top: beside, left: leftOf }
    }

    return { top: beside, left: fitLeft(rect.left) }
}

function clamp(value: number, low: number, high: number): number {
    return Math.min(Math.max(value, low), high)
}

/** Four dimmed rectangles around the anchor. No mask, nothing to composite wrong. */
function Spotlight({ rect, width, height }: { rect: Rect; width: number; height: number }) {
    const dim = 'absolute bg-ink-950/80'
    const glide = { transition: `all ${DURATION}ms ${EASE}` }
    return (
        <>
            <div className={dim} style={{ ...glide, top: 0, left: 0, width, height: Math.max(0, rect.top) }} />
            <div className={dim} style={{
                ...glide,
                top: rect.top + rect.height, left: 0, width,
                height: Math.max(0, height - rect.top - rect.height),
            }} />
            <div className={dim} style={{
                ...glide, top: rect.top, left: 0, width: Math.max(0, rect.left), height: rect.height,
            }} />
            <div className={dim} style={{
                ...glide,
                top: rect.top, left: rect.left + rect.width,
                width: Math.max(0, width - rect.left - rect.width), height: rect.height,
            }} />
        </>
    )
}
