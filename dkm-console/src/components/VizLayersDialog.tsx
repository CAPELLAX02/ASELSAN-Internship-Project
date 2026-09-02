import { useEffect } from 'react'

import { useT } from '../i18n/useT'
import { Icon } from './Icon'

export interface LayerEntry {
    type: string
    label: string
    kindLabel: string
    color: string
    note?: string
}

/**
 * What the plan view draws, chosen by the operator.
 *
 * <p>The legend can carry this while there are seven types on it. It cannot
 * carry twenty: the corner it lives in is finite, and a list long enough to
 * scroll is a list that covers the display it is describing. So the legend stays
 * a legend, and the choosing moves here -- where each type gets a full row, its
 * own colour swatch, the shape it draws as, and the sentence from the catalog
 * explaining what it means.
 *
 * <p>The catalog's own note rides on each row as a tooltip rather than inline:
 * it is the schema author's prose, untranslated, and running English paragraphs
 * through a Turkish dialog reads as an oversight -- while inlining seven of them
 * turns a list that fits into one that scrolls.
 *
 * <p>Grouped by shape rather than by link, because the question being answered
 * is "what is making the screen busy", and the answer is almost always a shape:
 * the sweep, the areas, the tracks.
 */
export function VizLayersDialog({ open, entries, hidden, onToggle, onAll, onNone, onClose }: {
    open: boolean
    entries: LayerEntry[]
    hidden: Set<string>
    onToggle: (type: string) => void
    onAll: () => void
    onNone: () => void
    onClose: () => void
}) {
    const t = useT()

    useEffect(() => {
        if (!open) return
        const onKey = (event: KeyboardEvent) => {
            if (event.key === 'Escape') { event.preventDefault(); onClose() }
        }
        window.addEventListener('keydown', onKey)
        return () => window.removeEventListener('keydown', onKey)
    }, [open, onClose])

    if (!open) return null

    const groups = new Map<string, LayerEntry[]>()
    for (const entry of entries) {
        const bucket = groups.get(entry.kindLabel)
        if (bucket) bucket.push(entry)
        else groups.set(entry.kindLabel, [entry])
    }

    const shown = entries.length - hidden.size

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-ink-950/70 p-4"
            role="presentation"
            onMouseDown={(event) => { if (event.target === event.currentTarget) onClose() }}
        >
            <div
                role="dialog"
                aria-modal="true"
                aria-labelledby="layers-title"
                className="panel w-full max-w-[34rem] max-h-[80vh] flex flex-col tour-step"
            >
                <div className="panel-title flex items-center gap-2">
                    <Icon name="layers" size={13} className="text-signal" />
                    <span id="layers-title" className="text-signal">{t('viz.layers.title')}</span>
                    <span className="ml-auto normal-case tracking-normal text-ink-500">
                        {t('viz.layers.count', { shown, total: entries.length })}
                    </span>
                </div>

                <p className="m-0 px-4 pt-3 text-ink-400">{t('viz.layers.body')}</p>
                <p className="m-0 px-4 pt-1 pb-3 text-ink-500">{t('viz.layers.hint')}</p>

                <div className="flex items-center gap-2 px-4 pb-3">
                    <button className="btn text-mini py-0.5" onClick={onAll}
                        disabled={hidden.size === 0}>
                        <Icon name="eye" size={11} />{t('viz.layers.all')}
                    </button>
                    <button className="btn text-mini py-0.5" onClick={onNone}
                        disabled={hidden.size === entries.length}>
                        <Icon name="eyeOff" size={11} />{t('viz.layers.none')}
                    </button>
                </div>

                <div className="flex-1 min-h-0 overflow-y-auto border-t border-ink-800">
                    {[...groups].map(([kind, rows]) => (
                        <div key={kind}>
                            <div className="px-4 py-1 bg-ink-900 text-ink-500 text-micro
                                            uppercase tracking-[0.06em] sticky top-0">
                                {kind}
                            </div>
                            {rows.map((entry) => {
                                const off = hidden.has(entry.type)
                                return (
                                    <button
                                        key={entry.type}
                                        className={`w-full flex items-start gap-3 px-4 py-2 text-left
                                                    border-b border-ink-850 hover:bg-ink-850
                                                    transition-colors duration-100
                                                    ${off ? 'opacity-55' : ''}`}
                                        aria-pressed={!off}
                                        onClick={() => onToggle(entry.type)}
                                        title={entry.note ?? entry.type}
                                    >
                                        <span className="pt-0.5 shrink-0 text-ink-400">
                                            <Icon name={off ? 'eyeOff' : 'eye'} size={13} />
                                        </span>
                                        <span
                                            className="w-2.5 h-2.5 mt-1 shrink-0"
                                            style={{
                                                background: off ? 'transparent' : entry.color,
                                                boxShadow: `inset 0 0 0 1px ${entry.color}`,
                                            }}
                                        />
                                        <span className="min-w-0 flex-1">
                                            <span className={`block text-ink-200 ${off ? 'line-through decoration-1' : ''}`}>
                                                {entry.label}
                                            </span>
                                            <span className="block text-ink-600 mt-0.5 font-[family-name:var(--font-mono)]">
                                                {entry.type}
                                            </span>
                                        </span>
                                    </button>
                                )
                            })}
                        </div>
                    ))}
                </div>

                <div className="flex justify-end gap-2 px-4 py-3 border-t border-ink-800">
                    <button className="btn btn-primary" onClick={onClose}>
                        {t('viz.layers.done')}
                    </button>
                </div>
            </div>
        </div>
    )
}
