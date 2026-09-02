import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * The seam between two panels, and the handle that moves it.
 *
 * <p>The layout has one reason to be adjustable: which panel matters depends on
 * what the operator is doing. Editing a message wants the inspector wide;
 * watching a run wants the plan view wide; chasing a framing error wants the log
 * tall. A fixed split serves one of those at the expense of the other two.
 *
 * <p>Sizes are kept per seam in `localStorage`, because a layout the operator
 * set and then lost on reload is worse than one they could never set at all.
 */

const MIN_FRACTION = 0.12
const MAX_FRACTION = 0.6

function read(key: string, fallback: number): number {
    try {
        const stored = window.localStorage.getItem(key)
        const value = stored === null ? NaN : Number(stored)
        return Number.isFinite(value) ? clamp(value) : fallback
    } catch {
        return fallback
    }
}

function clamp(value: number) {
    return Math.min(MAX_FRACTION, Math.max(MIN_FRACTION, value))
}

/**
 * A stored fraction of the container, and the setter the handle drives.
 *
 * @param key    where this seam remembers itself
 * @param initial the fraction to use when nothing is stored
 */
export function useSplit(key: string, initial: number) {
    const [fraction, setFraction] = useState(() => read(`dkm.split.${key}`, initial))

    const commit = useCallback((next: number) => {
        const value = clamp(next)
        setFraction(value)
        try {
            window.localStorage.setItem(`dkm.split.${key}`, String(value))
        } catch {
            // A viewer with site data blocked still gets to drag the seam; it
            // just will not be there next time.
        }
    }, [key])

    const reset = useCallback(() => commit(initial), [commit, initial])
    // Whether this seam has been moved off its default, so a reset control can
    // say there is nothing to reset rather than sitting there doing nothing.
    const moved = Math.abs(fraction - initial) > 0.001
    return { fraction, commit, reset, moved }
}

/** Every seam's stored key, so one control can put the layout back. */
export const SPLIT_KEYS = ['left', 'right', 'log'] as const

export function Splitter({ orientation, onDrag, onReset, label }: {
    orientation: 'vertical' | 'horizontal'
    /** Called with the pointer position in client coordinates. */
    onDrag: (clientX: number, clientY: number) => void
    onReset: () => void
    label: string
}) {
    const vertical = orientation === 'vertical'
    const [dragging, setDragging] = useState(false)
    const latest = useRef(onDrag)
    latest.current = onDrag

    useEffect(() => {
        if (!dragging) return
        const move = (event: PointerEvent) => {
            event.preventDefault()
            latest.current(event.clientX, event.clientY)
        }
        const stop = () => setDragging(false)
        window.addEventListener('pointermove', move)
        window.addEventListener('pointerup', stop)
        window.addEventListener('pointercancel', stop)
        // While a seam is moving, the pointer is a resize arrow everywhere --
        // not only over the four pixels it started on.
        const previous = document.body.style.cursor
        document.body.style.cursor = vertical ? 'col-resize' : 'row-resize'
        document.body.style.userSelect = 'none'
        return () => {
            window.removeEventListener('pointermove', move)
            window.removeEventListener('pointerup', stop)
            window.removeEventListener('pointercancel', stop)
            document.body.style.cursor = previous
            document.body.style.userSelect = ''
        }
    }, [dragging, vertical])

    return (
        <div
            role="separator"
            aria-orientation={vertical ? 'vertical' : 'horizontal'}
            aria-label={label}
            title={label}
            tabIndex={0}
            className={`group relative shrink-0 bg-ink-700 touch-none
                        ${vertical ? 'w-px cursor-col-resize' : 'h-px cursor-row-resize'}
                        ${dragging ? 'bg-signal' : 'hover:bg-signal-dim'}
                        transition-colors duration-100`}
            onPointerDown={(event) => {
                event.preventDefault()
                setDragging(true)
            }}
            onDoubleClick={onReset}
            onKeyDown={(event) => {
                // Reachable without a mouse, and resettable without remembering
                // that double-click does it.
                if (event.key === 'Home') { event.preventDefault(); onReset() }
            }}
        >
            {/* The seam is a hairline; the thing you can grab is wider than the
                thing you can see, which is what makes it feel accurate. */}
            <span
                className={`absolute ${vertical
                    ? '-left-[3px] -right-[3px] inset-y-0'
                    : '-top-[3px] -bottom-[3px] inset-x-0'}`}
            />
        </div>
    )
}
