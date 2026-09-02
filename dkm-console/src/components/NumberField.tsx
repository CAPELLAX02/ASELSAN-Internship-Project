import { useEffect, useRef, useState } from 'react'

/**
 * Strips a number back to how a person would have written it.
 *
 * <p>`01`, `0500` and `3.50` are all legal for the browser and all wrong on
 * screen: the operator typed a digit into a field showing `0` and got a number
 * with a leading zero, which reads like a fixed-width field or a code rather
 * than a quantity. The value was never wrong -- `Number('0500')` is 500 -- but
 * a field that shows one thing and means another is a field people stop
 * trusting.
 *
 * <p>Done on the string rather than by round-tripping through `Number` so that
 * a half-typed value survives: `-`, `1.` and `0.` are all states on the way to
 * something valid, and normalising them away moves the caret out from under the
 * operator mid-keystroke.
 */
export function normaliseNumeric(text: string): string {
    if (text === '') return ''
    const sign = text.startsWith('-') ? '-' : ''
    let rest = sign ? text.slice(1) : text
    // A lone separator is a fine thing to be holding while typing.
    if (rest === '' || rest === '.') return sign + rest
    const dot = rest.indexOf('.')
    let whole = dot === -1 ? rest : rest.slice(0, dot)
    const fraction = dot === -1 ? '' : rest.slice(dot)
    whole = whole.replace(/^0+(?=\d)/, '')
    if (whole === '') whole = '0'
    return sign + whole + fraction
}

/**
 * A numeric field whose displayed text is always canonical.
 *
 * <p>Controlled by a draft string rather than by the number itself. A purely
 * numeric controlled input cannot correct `00`, because the number it parses to
 * is the number it already had, React sees no change and leaves the DOM alone --
 * which is exactly the case where the display is wrong.
 */
export function NumberField({
    value, onChange, className = 'field', disabled, min, max, step, title, integer = false,
    onKeyDown,
}: {
    value: number
    onChange: (value: number) => void
    className?: string
    disabled?: boolean
    min?: number
    max?: number
    step?: number | 'any'
    title?: string
    /** Reject a decimal point outright, for fields that count things. */
    integer?: boolean
    onKeyDown?: (event: React.KeyboardEvent<HTMLInputElement>) => void
}) {
    const [draft, setDraft] = useState(() => String(value))
    const editing = useRef(false)

    // Follow the value while the operator is not the one changing it: a retime
    // elsewhere, a different message selected, a reset.
    useEffect(() => {
        if (editing.current) return
        setDraft(String(value))
    }, [value])

    return (
        <input
            className={className}
            type="text"
            inputMode={integer ? 'numeric' : 'decimal'}
            value={draft}
            disabled={disabled}
            title={title}
            onKeyDown={onKeyDown}
            onFocus={() => { editing.current = true }}
            onBlur={() => {
                editing.current = false
                // Whatever is left half-typed resolves to the committed number.
                setDraft(String(value))
            }}
            onChange={(event) => {
                const raw = event.target.value
                const allowed = integer ? /^-?\d*$/ : /^-?\d*\.?\d*$/
                if (!allowed.test(raw)) return
                const next = normaliseNumeric(raw)
                setDraft(next)
                const parsed = Number(next)
                if (next === '' || next === '-' || !Number.isFinite(parsed)) return
                if (min !== undefined && parsed < min) return
                if (max !== undefined && parsed > max) return
                onChange(parsed)
            }}
            // Kept for the arrow keys and for anything reading the DOM.
            data-step={step}
            data-min={min}
            data-max={max}
        />
    )
}
