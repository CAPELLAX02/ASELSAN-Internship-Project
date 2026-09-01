import { useEffect } from 'react'

import { useStore, type Toast as ToastData, type ToastLevel } from '../store/useStore'
import { Icon, type IconName } from './Icon'

/**
 * Transient feedback, stacked under the top bar.
 *
 * <p>Top centre rather than a corner: this interface's corners are where the
 * numbers an operator is watching live, and a toast that lands on the lag
 * readout hides the thing it is commenting on. Coming down from the top puts it
 * next to the controls that caused it and leaves the data alone.
 *
 * <p>A toast is deliberately the opposite of the surface behind it -- pale on
 * the dark theme, dark on the light one. Everything else on screen is a panel
 * in the theme's own values, so another one of those would have to shout in
 * colour to be noticed at all; inverting means it is seen before it is read,
 * and the colour is left free to say which of the four kinds it is.
 *
 * <p>Four levels, each with its own dwell. An error stays until it is dismissed
 * -- it is the one kind that must not scroll past while the operator is looking
 * elsewhere.
 */
const DWELL: Record<ToastLevel, number> = {
    success: 3500,
    info: 4000,
    warning: 7000,
    error: 0,
}

const TONE: Record<ToastLevel, string> = {
    success: 'text-toast-success',
    info: 'text-toast-info',
    warning: 'text-toast-warning',
    error: 'text-toast-error',
}

const MARK: Record<ToastLevel, IconName> = {
    success: 'check',
    info: 'info',
    warning: 'warning',
    error: 'close',
}

/**
 * How many are legible at once.
 *
 * <p>Loading a file can fire three in a row, and a column of six pushes the
 * newest one down over the transport controls. Beyond this the older ones tuck
 * behind the stack -- still there, still counted, just not competing.
 */
const MAX_VISIBLE = 3

/** Depth in the stack: 0 is the newest, and the last two peek out behind it. */
const DEPTH = [
    { offset: 0, scale: 1, opacity: 1 },
    { offset: 6, scale: 0.965, opacity: 0.72 },
    { offset: 11, scale: 0.93, opacity: 0.45 },
]

export function ToastStack() {
    const toasts = useStore((s) => s.toasts)
    const dismiss = useStore((s) => s.dismissToast)

    if (toasts.length === 0) return null

    // Newest first: the one that just happened is the one in front.
    const shown = [...toasts].reverse().slice(0, MAX_VISIBLE)

    return (
        <div className="fixed top-2 left-1/2 -translate-x-1/2 z-50 w-full max-w-xl px-4 pointer-events-none">
            {/* A stack, not a list: each card sits on the same spot, the ones
                behind pushed down and scaled back so the pile reads as depth
                rather than as three separate things to read. */}
            <div className="relative">
                {shown.map((toast, index) => {
                    const depth = DEPTH[Math.min(index, DEPTH.length - 1)]
                    return (
                        <div
                            key={toast.id}
                            className={index === 0 ? 'toast-in' : 'toast-settle'}
                            style={{
                                position: index === 0 ? 'relative' : 'absolute',
                                top: index === 0 ? undefined : 0,
                                left: 0,
                                right: 0,
                                zIndex: MAX_VISIBLE - index,
                                transform: `translateY(${depth.offset}px) scale(${depth.scale})`,
                                opacity: depth.opacity,
                            }}
                        >
                            <ToastRow
                                toast={toast}
                                buried={index > 0}
                                onDismiss={() => dismiss(toast.id)}
                            />
                        </div>
                    )
                })}
            </div>
        </div>
    )
}

function ToastRow({ toast, buried, onDismiss }: {
    toast: ToastData
    /** Behind another card: readable as presence, but not the one being read. */
    buried: boolean
    onDismiss: () => void
}) {
    const dwell = DWELL[toast.level]

    useEffect(() => {
        if (dwell === 0) return
        const timer = window.setTimeout(onDismiss, dwell)
        return () => window.clearTimeout(timer)
    }, [dwell, onDismiss])

    return (
        <div
            role={toast.level === 'error' ? 'alert' : 'status'}
            className={`w-full flex items-start gap-2.5 border px-3 py-2 shadow-lg
                        bg-toast-bg border-toast-line text-toast-fg
                        ${buried ? 'pointer-events-none' : 'pointer-events-auto'}`}
        >
            <span aria-hidden="true"
                className={`shrink-0 mt-px flex items-center justify-center ${TONE[toast.level]}`}>
                <Icon name={MARK[toast.level]} size={14} strokeWidth={2.2} />
            </span>
            <span className="min-w-0 flex-1">{toast.message}</span>
            {toast.detail && <span className="shrink-0 num text-toast-mute">{toast.detail}</span>}
            <button
                className="shrink-0 text-toast-mute hover:text-toast-fg leading-none"
                onClick={onDismiss}
                aria-label="×"
            >
                <Icon name="close" size={12} />
            </button>
        </div>
    )
}
