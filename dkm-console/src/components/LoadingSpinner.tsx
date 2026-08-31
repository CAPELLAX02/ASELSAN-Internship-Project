import { useT } from '../i18n/useT'

/**
 * The one spinner in the interface.
 *
 * <p>A single component rather than a per-panel improvisation, because a
 * loading indicator that looks different in each corner reads as three
 * different things happening rather than one thing being slow.
 *
 * <p>Drawn as SVG strokes rather than a spinning border box: at 12px a border
 * trick loses its ring to rounding on non-integer device pixel ratios, and this
 * one stays legible at every size the interface uses.
 */
export function LoadingSpinner({ size = 14, className = '' }: {
    size?: number
    /** Extra classes, normally a colour. Inherits `currentColor` otherwise. */
    className?: string
}) {
    const t = useT()
    return (
        <svg
            width={size}
            height={size}
            viewBox="0 0 24 24"
            fill="none"
            className={`spin shrink-0 ${className}`}
            role="status"
            aria-label={t('app.loading')}
        >
            <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="3" opacity="0.22" />
            <path
                d="M21 12a9 9 0 0 0-9-9"
                stroke="currentColor"
                strokeWidth="3"
                strokeLinecap="round"
            />
        </svg>
    )
}

/** Spinner plus a word, for a panel that has room to say what it is waiting for. */
export function LoadingLine({ label, className = '' }: { label?: string; className?: string }) {
    const t = useT()
    return (
        <div className={`flex items-center justify-center gap-2 py-6 text-ink-400 ${className}`}>
            <LoadingSpinner />
            <span>{label ?? t('app.loading')}</span>
        </div>
    )
}

/**
 * Sits over a panel that already has content while that content is being
 * replaced. The old content stays readable underneath rather than collapsing to
 * an empty box, so the layout does not jump on every refetch.
 */
export function LoadingOverlay({ show }: { show: boolean }) {
    if (!show) return null
    return (
        <div className="absolute inset-0 z-20 flex items-start justify-center pt-8
                        bg-ink-950/45 backdrop-blur-[1px] pointer-events-none">
            <LoadingSpinner size={20} className="text-signal" />
        </div>
    )
}
