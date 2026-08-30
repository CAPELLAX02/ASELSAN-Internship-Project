import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import { api } from '../api/client'
import type { MessagePage, MessageSummary, SortDir, SortKey, TraceRow } from '../api/types'
import { useT, type Translate } from '../i18n/useT'
import { useStore } from '../store/useStore'
import { clockTime, count } from './format'

export type ListMode = 'input' | 'output' | 'trace'
export interface Selection { mode: 'input' | 'output'; id: number }

const ROW_HEIGHT = 26
const PAGE = 500
const OVERSCAN = 8

const SORTS: SortKey[] = ['sequence', 'timestamp', 'type', 'link', 'length', 'wallclock']

/**
 * The message lists (FR-7, FR-29, FR-31) and the chronological trace (FR-32).
 *
 * <p>Windowed by hand rather than with a virtualization library: rows are a
 * fixed height, so the visible slice is arithmetic, and a capture that runs to
 * millions of messages costs the same to scroll as one with ten.
 *
 * <p>Sorting and filtering are asked of the gateway rather than applied here.
 * The browser only ever holds one page, so sorting client-side would sort the
 * page and not the list — a control that looks like it works right up until the
 * list is long enough to matter.
 */
export function MessagePanel({ selection, onSelect }: {
    selection: Selection | null
    onSelect: (selection: Selection | null) => void
}) {
    const t = useT()
    const [mode, setMode] = useState<ListMode>('input')
    const [link, setLink] = useState('')
    const [type, setType] = useState('')
    const [status, setStatus] = useState('')
    const [sort, setSort] = useState<SortKey>('sequence')
    const [dir, setDir] = useState<SortDir>('asc')
    const [follow, setFollow] = useState(true)
    const [page, setPage] = useState<MessagePage | null>(null)
    const [trace, setTrace] = useState<TraceRow[]>([])
    const [offset, setOffset] = useState(0)
    const [error, setError] = useState<string | null>(null)

    const schema = useStore((s) => s.schema)
    const sessionVersion = useStore((s) => s.sessionVersion)
    const captureVersion = useStore((s) => s.captureVersion)
    const captureOverflowed = useStore((s) => s.captureOverflowed)
    const playbackSent = useStore((s) => s.playback.sent)

    const scrollRef = useRef<HTMLDivElement | null>(null)
    const [viewport, setViewport] = useState({ top: 0, height: 400 })

    const version = mode === 'input' ? sessionVersion : captureVersion

    const load = useCallback(async () => {
        try {
            if (mode === 'trace') {
                const result = await api.trace({ link, limit: 1000 })
                setTrace(result.items)
            } else if (mode === 'input') {
                setPage(await api.sessionMessages({ link, type, status, sort, dir, offset, limit: PAGE }))
            } else {
                setPage(await api.captureMessages({ link, type, sort, dir, offset, limit: PAGE, tail: follow }))
            }
            setError(null)
        } catch (cause) {
            setError((cause as Error).message)
        }
    }, [mode, link, type, status, sort, dir, offset, follow])

    // Synchronising with the gateway. Pages arrive asynchronously, and the trigger
    // is a version counter the server bumped — not something derivable here.
    useEffect(() => { void load() }, [load, version, sessionVersion, playbackSent])

    // Changing a filter changes which messages exist, so page 3 of the old filter
    // is meaningless under the new one. Reset from the handler rather than an
    // effect: an effect would render one frame of a page that does not exist.
    const changeFilter = (apply: () => void) => {
        apply()
        setOffset(0)
        // Scroll back to the top as well. Keeping the pixel offset across a re-sort
        // drops the reader into the middle of a list they have not seen the start
        // of, which reads as the sort having done nothing.
        if (scrollRef.current) {
            scrollRef.current.scrollTop = 0
        }
    }

    useEffect(() => {
        const element = scrollRef.current
        if (!element) return
        const update = () => setViewport({ top: element.scrollTop, height: element.clientHeight })
        update()
        element.addEventListener('scroll', update, { passive: true })
        const observer = new ResizeObserver(update)
        observer.observe(element)
        return () => {
            element.removeEventListener('scroll', update)
            observer.disconnect()
        }
    }, [])

    useEffect(() => {
        if ((mode === 'output' && follow) || mode === 'trace') {
            const element = scrollRef.current
            if (element) element.scrollTop = element.scrollHeight
        }
    }, [mode, follow, page, trace])

    const items: (MessageSummary | TraceRow)[] = mode === 'trace' ? trace : (page?.items ?? [])
    const first = Math.max(0, Math.floor(viewport.top / ROW_HEIGHT) - OVERSCAN)
    const last = Math.min(items.length, Math.ceil((viewport.top + viewport.height) / ROW_HEIGHT) + OVERSCAN)
    const visible = items.slice(first, last)

    const types = useMemo(() => {
        if (!schema) return []
        return schema.messages
            .filter((m) => (mode === 'output' ? m.direction !== 'TO_DKM' : m.direction !== 'FROM_DKM'))
            .map((m) => m.qualifiedName)
    }, [schema, mode])

    return (
        <div className="panel min-h-0 flex-1" data-tour="list">
            <div className="panel-title">
                <span className="flex items-center gap-1">
                    <Tab active={mode === 'input'} onClick={() => changeFilter(() => setMode('input'))}>
                        {t('list.stimulus')}
                    </Tab>
                    <Tab active={mode === 'output'} onClick={() => changeFilter(() => setMode('output'))}>
                        {t('list.capture')}
                    </Tab>
                    <Tab active={mode === 'trace'} onClick={() => changeFilter(() => setMode('trace'))}>
                        {t('list.trace')}
                    </Tab>
                </span>
                <span className="normal-case tracking-normal text-ink-400">
                    {mode === 'trace'
                        ? count(trace.length)
                        : page
                            ? t('list.counts', { filtered: count(page.filtered), total: count(page.total) })
                            : '…'}
                </span>
            </div>

            <div className="flex items-center gap-1.5 px-2 py-1.5 border-b border-ink-700 bg-ink-850/60 flex-wrap">
                <select
                    className="field w-24 py-0.5"
                    aria-label={t('list.allLinks')}
                    value={link}
                    onChange={(e) => changeFilter(() => setLink(e.target.value))}
                >
                    <option value="">{t('list.allLinks')}</option>
                    {schema?.modules.filter((m) => !m.dkm).map((m) => (
                        <option key={m.name} value={m.name}>{m.name}</option>
                    ))}
                </select>

                {mode !== 'trace' && (
                    <>
                        <select
                            className="field flex-1 min-w-[9rem] py-0.5"
                            aria-label={t('list.allTypes')}
                            value={type}
                            onChange={(e) => changeFilter(() => setType(e.target.value))}
                        >
                            <option value="">{t('list.allTypes')}</option>
                            {types.map((name) => <option key={name} value={name}>{name}</option>)}
                        </select>

                        {/* FR-29: sorting is asked of the gateway, so it applies to the whole
                filtered set rather than the page that happens to be loaded. */}
                        <select
                            className="field w-32 py-0.5"
                            aria-label={t('list.sort')}
                            value={sort}
                            onChange={(e) => changeFilter(() => setSort(e.target.value as SortKey))}
                        >
                            {SORTS.map((key) => (
                                <option key={key} value={key}>{t(`list.sort.${key}` as never)}</option>
                            ))}
                        </select>
                        <button
                            className="btn py-0.5 px-1.5 text-mini w-7"
                            onClick={() => changeFilter(() => setDir(dir === 'asc' ? 'desc' : 'asc'))}
                            title={dir === 'asc' ? t('list.sortAsc') : t('list.sortDesc')}
                            aria-label={dir === 'asc' ? t('list.sortAsc') : t('list.sortDesc')}
                        >
                            {dir === 'asc' ? '↑' : '↓'}
                        </button>
                    </>
                )}

                {mode === 'input' && (
                    <select
                        className="field w-28 py-0.5"
                        aria-label={t('list.statusAll')}
                        value={status}
                        onChange={(e) => changeFilter(() => setStatus(e.target.value))}
                    >
                        <option value="">{t('list.statusAll')}</option>
                        <option value="pending">{t('list.statusPending')}</option>
                        <option value="sent">{t('list.statusSent')}</option>
                        <option value="problem">{t('list.statusProblem')}</option>
                    </select>
                )}

                {mode === 'output' && (
                    <button
                        className={`btn py-0.5 text-mini ${follow ? 'btn-primary' : ''}`}
                        onClick={() => setFollow((value) => !value)}
                        title={t('list.followTitle')}
                    >
                        {t('list.follow')}
                    </button>
                )}
            </div>

            {mode === 'trace' && (
                <div className="px-2 py-1 border-b border-ink-700 text-micro text-ink-500">
                    {t('trace.hint')}
                </div>
            )}

            {captureOverflowed > 0 && mode === 'output' && (
                <div className="px-2 py-1 bg-danger/15 border-b border-danger/40 text-danger text-mini">
                    {t('list.overflow', { count: count(captureOverflowed) })}
                </div>
            )}

            {error && (
                <div className="px-2 py-1 bg-danger/15 border-b border-danger/40 text-danger text-mini">
                    {error}
                </div>
            )}

            <div ref={scrollRef} className="flex-1 overflow-auto min-h-0 relative">
                <div style={{ height: items.length * ROW_HEIGHT }} className="relative">
                    {visible.map((item, index) => mode === 'trace'
                        ? (
                            <TraceRowView
                                key={`${(item as TraceRow).direction}-${item.id}`}
                                t={t}
                                row={item as TraceRow}
                                top={(first + index) * ROW_HEIGHT}
                                selected={selection?.id === item.id}
                                onClick={() => onSelect({
                                    mode: (item as TraceRow).direction === 'IN' ? 'output' : 'input',
                                    id: item.id,
                                })}
                            />
                        )
                        : (
                            <Row
                                key={item.id}
                                t={t}
                                item={item as MessageSummary}
                                top={(first + index) * ROW_HEIGHT}
                                selected={selection?.mode === mode && selection.id === item.id}
                                onClick={() => onSelect({ mode: mode as 'input' | 'output', id: item.id })}
                            />
                        ))}
                </div>
                {items.length === 0 && (
                    <div className="p-4 text-ink-500 text-center">
                        {mode === 'input' ? t('list.emptyStimulus')
                            : mode === 'output' ? t('list.emptyCapture')
                                : t('list.emptyTrace')}
                    </div>
                )}
            </div>

            {mode !== 'trace' && page && page.filtered > PAGE && (
                <div className="flex items-center justify-between px-2 py-1 border-t border-ink-700 text-mini">
                    <button className="btn py-0.5" disabled={offset === 0}
                        onClick={() => setOffset(Math.max(0, offset - PAGE))}>{t('list.previous')}</button>
                    <span className="text-ink-400">
                        {t('list.range', {
                            from: count(offset + 1),
                            to: count(Math.min(offset + PAGE, page.filtered)),
                        })}
                    </span>
                    <button className="btn py-0.5" disabled={offset + PAGE >= page.filtered}
                        onClick={() => setOffset(offset + PAGE)}>{t('list.next')}</button>
                </div>
            )}
        </div>
    )
}

function Tab({ active, onClick, children }: {
    active: boolean; onClick: () => void; children: React.ReactNode
}) {
    return (
        <button
            onClick={onClick}
            aria-pressed={active}
            className={`px-2 py-0.5 rounded text-micro uppercase tracking-[0.14em] transition-colors ${active ? 'bg-signal-dim/30 text-signal' : 'text-ink-400 hover:text-ink-200'
                }`}
        >
            {children}
        </button>
    )
}

function Row({ t, item, top, selected, onClick }: {
    t: Translate; item: MessageSummary; top: number; selected: boolean; onClick: () => void
}) {
    // FR-31: three visually distinct states — editable stimulus, sent history,
    // and read-only capture — plus a fourth for anything that cannot be trusted.
    const tone = item.problem
        ? 'text-danger'
        : item.direction === 'FROM_DKM'
            ? 'text-signal'
            : item.sent
                ? 'text-ink-400'
                : 'text-ink-100'

    return (
        <button
            onClick={onClick}
            style={{ top, height: ROW_HEIGHT }}
            className={`absolute left-0 right-0 flex items-center gap-2 px-2 text-left border-l-2 ${selected
                    ? 'bg-signal-dim/20 border-l-signal'
                    : `border-l-transparent hover:bg-ink-800/70 ${item.sent && !item.problem ? 'bg-ink-950/40' : ''}`
                }`}
            title={item.problem ?? item.preview}
        >
            <span className="w-20 shrink-0 truncate text-ink-500 tabular-nums">{item.timestamp}</span>
            <span className="w-10 shrink-0 text-ink-400">{item.link ?? '?'}</span>
            <span className={`w-40 shrink-0 truncate ${tone}`}>
                {item.type ?? `msg_id ${item.msgId}`}
            </span>
            <span className="flex-1 truncate text-ink-500">{item.preview}</span>
            <span className="w-[68px] shrink-0 truncate text-right text-micro text-ink-500">
                {item.problem ? t('row.blocked')
                    : item.sent ? t('row.sent')
                        : item.direction === 'FROM_DKM' ? '' : t('row.pending')}
            </span>
        </button>
    )
}

/** One line of the chronological trace: which way it went, and how long after the last one. */
function TraceRowView({ t, row, top, selected, onClick }: {
    t: Translate; row: TraceRow; top: number; selected: boolean; onClick: () => void
}) {
    const inbound = row.direction === 'IN'
    return (
        <button
            onClick={onClick}
            style={{ top, height: ROW_HEIGHT }}
            className={`absolute left-0 right-0 flex items-center gap-2 px-2 text-left border-l-2 ${selected ? 'bg-signal-dim/20 border-l-signal' : 'border-l-transparent hover:bg-ink-800/70'
                }`}
            title={row.problem ?? row.preview}
        >
            <span className="w-24 shrink-0 text-ink-500 tabular-nums">{clockTime(row.wallClock)}</span>
            <span className={`w-6 shrink-0 ${inbound ? 'text-signal' : 'text-ink-300'}`}
                title={inbound ? t('row.in') : t('row.out')}>
                {inbound ? '←' : '→'}
            </span>
            <span className="w-12 shrink-0 text-right text-ink-600 tabular-nums">
                {row.deltaMillis > 0 ? `+${row.deltaMillis}` : ''}
            </span>
            <span className="w-10 shrink-0 text-ink-400">{row.link ?? '?'}</span>
            <span className={`w-40 shrink-0 truncate ${inbound ? 'text-signal' : 'text-ink-100'}`}>
                {row.type ?? `msg_id ${row.msgId}`}
            </span>
            <span className="flex-1 truncate text-ink-500">{row.preview}</span>
        </button>
    )
}
