/**
 * A two-state control that says what both states mean.
 *
 * <p>Used where a checkbox would leave the reader guessing what the unchecked
 * state does. "Insert after the selected message" as a checkbox says nothing
 * about where an unchecked insert goes; the same choice as two labelled halves
 * says it without being asked.
 */
export function Segmented<T extends string>({ value, options, onChange, label, disabled = false }: {
    value: T
    options: { value: T; label: string; title?: string }[]
    onChange: (value: T) => void
    /** Shown before the control. Omit where the surrounding row already says it. */
    label?: string
    disabled?: boolean
}) {
    return (
        <div className="flex items-center gap-2 min-w-0">
            {label && <span className="text-ink-400 shrink-0">{label}</span>}
            <div
                role="radiogroup"
                aria-label={label}
                className={`inline-flex  border border-ink-600 overflow-hidden bg-ink-850
                            ${disabled ? 'opacity-40 pointer-events-none' : ''}`}
            >
                {options.map((option) => {
                    const active = option.value === value
                    return (
                        <button
                            key={option.value}
                            role="radio"
                            aria-checked={active}
                            title={option.title}
                            disabled={disabled}
                            onClick={() => onChange(option.value)}
                            className={`px-2 py-0.5 text-mini transition-colors whitespace-nowrap
                                ${active
                                    ? 'bg-signal-dim/35 text-signal'
                                    : 'text-ink-400 hover:text-ink-100 hover:bg-ink-800'}`}
                        >
                            {option.label}
                        </button>
                    )
                })}
            </div>
        </div>
    )
}

/** A labelled on/off toggle, for a setting whose off state needs no explaining. */
export function Toggle({ checked, onChange, label, title, disabled = false }: {
    checked: boolean
    onChange: (checked: boolean) => void
    label: string
    title?: string
    disabled?: boolean
}) {
    return (
        <button
            role="switch"
            aria-checked={checked}
            title={title}
            disabled={disabled}
            onClick={() => onChange(!checked)}
            className={`inline-flex items-center gap-2 text-mini transition-colors
                        ${disabled ? 'opacity-40 pointer-events-none' : ''}
                        ${checked ? 'text-signal' : 'text-ink-400 hover:text-ink-200'}`}
        >
            <span
                className={`relative w-7 h-4  border transition-colors shrink-0
                            ${checked ? 'bg-signal-dim/45 border-signal-dim' : 'bg-ink-800 border-ink-600'}`}
            >
                <span
                    className={`absolute top-[2px] w-[10px] h-[10px]  transition-all
                                ${checked ? 'left-[14px] bg-signal' : 'left-[2px] bg-ink-400'}`}
                />
            </span>
            {label}
        </button>
    )
}
