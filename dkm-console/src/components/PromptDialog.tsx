import { useEffect, useRef, useState } from 'react'

import { useT } from '../i18n/useT'
import { LoadingSpinner } from './LoadingSpinner'

/**
 * Asks for one value, in the interface's own voice.
 *
 * <p>The native `prompt` is the last place in this tool where a browser chrome
 * box could still appear, and it is the worst of them: it cannot say what the
 * value is for, cannot show the units, cannot validate, and blocks the whole
 * page while it waits. Here the field can be a number field with a unit beside
 * it, the hint can explain what the value does, and Enter submits.
 */
export function PromptDialog({
    open, title, body, hint, label, unit, initial, kind = 'text',
    confirmLabel, busy = false, validate, onConfirm, onCancel,
}: {
    open: boolean
    title: string
    body?: string
    /** A line under the field, for what the value will do. */
    hint?: string
    label: string
    /** Shown after the field: ms, bytes, whatever it is. */
    unit?: string
    initial: string
    kind?: 'text' | 'number'
    confirmLabel: string
    busy?: boolean
    /** Returns a message when the value cannot be used, or null when it can. */
    validate?: (value: string) => string | null
    onConfirm: (value: string) => void
    onCancel: () => void
}) {
    const t = useT()
    const [value, setValue] = useState(initial)
    const field = useRef<HTMLInputElement | null>(null)

    useEffect(() => {
        if (!open) return
        setValue(initial)
        // Selected, not just focused: the common case is replacing the value.
        const timer = window.setTimeout(() => field.current?.select(), 0)
        const onKey = (event: KeyboardEvent) => {
            if (event.key === 'Escape') { event.preventDefault(); onCancel() }
        }
        window.addEventListener('keydown', onKey)
        return () => {
            window.clearTimeout(timer)
            window.removeEventListener('keydown', onKey)
        }
    }, [open, initial, onCancel])

    if (!open) return null

    const problem = validate?.(value) ?? null
    const submit = () => { if (!problem && !busy) onConfirm(value) }

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-ink-950/70 p-4"
            role="presentation"
            onMouseDown={(event) => { if (event.target === event.currentTarget) onCancel() }}
        >
            <form
                role="dialog"
                aria-modal="true"
                aria-labelledby="prompt-title"
                className="panel w-full max-w-[26rem] tour-step"
                onSubmit={(event) => { event.preventDefault(); submit() }}
            >
                <div className="panel-title">
                    <span id="prompt-title" className="text-signal">{title}</span>
                </div>

                <div className="p-4 flex flex-col gap-3">
                    {body && <p className="text-ink-300 m-0">{body}</p>}

                    <label className="flex items-center gap-2">
                        <span className="w-24 shrink-0 text-ink-400">{label}</span>
                        <span className="flex-1 flex items-center gap-2 min-w-0">
                            <input
                                ref={field}
                                className="field"
                                type={kind}
                                value={value}
                                onChange={(event) => setValue(event.target.value)}
                            />
                            {unit && <span className="text-ink-500 shrink-0">{unit}</span>}
                        </span>
                    </label>

                    {hint && !problem && <p className="m-0 text-ink-500">{hint}</p>}
                    {problem && <p className="m-0 text-danger">{problem}</p>}
                </div>

                <div className="flex justify-end gap-2 px-4 pb-4">
                    <button type="button" className="btn" onClick={onCancel} disabled={busy}>
                        {t('dialog.cancel')}
                    </button>
                    <button
                        type="submit"
                        className="btn btn-primary flex items-center gap-1.5"
                        disabled={busy || problem !== null}
                    >
                        {busy && <LoadingSpinner size={12} />}
                        {confirmLabel}
                    </button>
                </div>
            </form>
        </div>
    )
}
