import { useEffect, useRef } from 'react'

import { useT } from '../i18n/useT'
import { LoadingSpinner } from './LoadingSpinner'

export type AlertTone = 'danger' | 'caution' | 'signal'

/**
 * Lifts the parts of a sentence the reader is actually deciding on.
 *
 * <p>Dialog copy in this tool is mostly one long sentence carrying two or three
 * facts -- a count, a timestamp, a message type -- and a wall of even text makes
 * the reader hunt for them. Numbers are set in the tabular face and bolded so
 * columns of them line up and stand out; anything that looks like a qualified
 * message name is set in the mono face, because that is what it will look like
 * in the list they are about to go and check.
 *
 * <p>Done by pattern rather than by markup in the strings so translators write
 * plain sentences and nothing depends on them remembering to tag a number.
 */
const EMPHASIS = /([A-Z][A-Za-z]*\/[A-Za-z]\w*)|((?:\d[\d .,]*\d|\d)(?:\s?(?:ms|B|KB|MB|s))?)/g

function emphasise(text: string): React.ReactNode[] {
    const parts: React.ReactNode[] = []
    let last = 0
    for (const match of text.matchAll(EMPHASIS)) {
        const at = match.index ?? 0
        if (at > last) parts.push(text.slice(last, at))
        parts.push(match[1]
            ? <code key={at} className="font-[family-name:var(--font-mono)] text-ink-100
                                        bg-ink-800 border border-ink-700 px-1">{match[0]}</code>
            : <b key={at} className="num text-ink-100 font-semibold">{match[0]}</b>)
        last = at + match[0].length
    }
    if (last < text.length) parts.push(text.slice(last))
    return parts
}

/**
 * A modal that asks before something irreversible happens.
 *
 * <p>Every destructive path in this interface goes through here rather than
 * `window.confirm`: the native dialog cannot say *what* is about to be lost,
 * and in this tool that detail is the whole point -- clearing a set the
 * operator has edited is a different act from clearing one loaded a second ago.
 *
 * <p>Focus moves to the confirming button on open and Escape cancels, so the
 * dialog is answerable without reaching for the mouse mid-run.
 */
export function AlertDialog({
    open, title, body, detail, tone = 'danger', confirmLabel, cancelLabel,
    busy = false, onConfirm, onCancel,
}: {
    open: boolean
    title: string
    body: string
    /** A second line for the specific thing at stake, when there is one. */
    detail?: string | null
    tone?: AlertTone
    confirmLabel: string
    cancelLabel?: string
    busy?: boolean
    onConfirm: () => void
    onCancel: () => void
}) {
    const t = useT()
    const confirmRef = useRef<HTMLButtonElement | null>(null)

    useEffect(() => {
        if (!open) return
        confirmRef.current?.focus()
        const onKey = (event: KeyboardEvent) => {
            if (event.key === 'Escape') {
                event.preventDefault()
                onCancel()
            }
        }
        window.addEventListener('keydown', onKey)
        return () => window.removeEventListener('keydown', onKey)
    }, [open, onCancel])

    if (!open) return null

    const accent = tone === 'danger' ? 'text-danger' : tone === 'caution' ? 'text-caution' : 'text-signal'
    const button = tone === 'danger' ? 'btn-danger' : 'btn-primary'

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-ink-950/70 p-4"
            role="presentation"
            onMouseDown={(event) => { if (event.target === event.currentTarget) onCancel() }}
        >
            <div
                role="alertdialog"
                aria-modal="true"
                aria-labelledby="alert-title"
                className="panel w-full max-w-[26rem] tour-step"
            >
                <div className="panel-title">
                    <span id="alert-title" className={accent}>{title}</span>
                </div>
                <div className="p-4 flex flex-col gap-2">
                    <p className="text-ink-200 m-0">{emphasise(body)}</p>
                    {detail && <p className={`m-0 ${accent}`}>{emphasise(detail)}</p>}
                </div>
                <div className="flex justify-end gap-2 px-4 pb-4">
                    <button className="btn" onClick={onCancel} disabled={busy}>
                        {cancelLabel ?? t('dialog.cancel')}
                    </button>
                    <button
                        ref={confirmRef}
                        className={`btn ${button} flex items-center gap-1.5`}
                        onClick={onConfirm}
                        disabled={busy}
                    >
                        {busy && <LoadingSpinner size={12} />}
                        {confirmLabel}
                    </button>
                </div>
            </div>
        </div>
    )
}
