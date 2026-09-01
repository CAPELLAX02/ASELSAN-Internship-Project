import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import { api } from '../api/client'
import type { MessagePage, MessageSummary, SortDir, SortKey, TraceRow } from '../api/types'
import { useT, type Translate } from '../i18n/useT'
import { useStore } from '../store/useStore'
import { Icon } from './Icon'
import { AlertDialog } from './AlertDialog'
import { clockTime, count } from './format'
import { LoadingOverlay, LoadingSpinner } from './LoadingSpinner'
import { Segmented, Toggle } from './Switch'

type ListMode = 'input' | 'output' | 'trace'
export interface Selection {
    mode: 'input' | 'output'
    id: number
    /** Position in the unfiltered list, when the list knew it. */
    index?: number
}

/** How the first column reads: from the start of the recording, or from the row above. */
type TimeMode = 'cumulative' | 'delta'

const ROW_HEIGHT = 26
const PAGE = 500
const OVERSCAN = 8

/** What Ctrl+V adds to the copied message's timestamp, in milliseconds. */
const PASTE_OFFSET_MS = 500

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
    const [timeMode, setTimeMode] = useState<TimeMode>('delta')
    const [follow, setFollow] = useState(true)
    const [page, setPage] = useState<MessagePage | null>(null)
    const [trace, setTrace] = useState<TraceRow[]>([])
    const [offset, setOffset] = useState(0)
    const [error, setError] = useState<string | null>(null)
    const [loading, setLoading] = useState(false)

    const [askClear, setAskClear] = useState(false)
    const [askRevert, setAskRevert] = useState(false)
    /** Keep the message currently going out in view during a run. */
    const [followRun, setFollowRun] = useState(true)
    const [askDelete, setAskDelete] = useState<MessageSummary | null>(null)
    const [dragId, setDragId] = useState<number | null>(null)
    const [dropIndex, setDropIndex] = useState<number | null>(null)

    const schema = useStore((s) => s.schema)
    const sessionVersion = useStore((s) => s.sessionVersion)
    const captureVersion = useStore((s) => s.captureVersion)
    const captureOverflowed = useStore((s) => s.captureOverflowed)
    const playbackSent = useStore((s) => s.playback.sent)
    const running = useStore((s) => s.playback.state === 'RUNNING')
    const run = useStore((s) => s.run)
    const notify = useStore((s) => s.notify)
    const touchSession = useStore((s) => s.touchSession)

    const scrollRef = useRef<HTMLDivElement | null>(null)
    const [viewport, setViewport] = useState({ top: 0, height: 400 })
    /** The last Ctrl+C. Kept per-session rather than on the system clipboard: a
     *  message is a typed structure, and round-tripping it through text would
     *  invite pasting something that is not one. */
    const clipboard = useRef<{ type: string; payload: unknown } | null>(null)

    const version = mode === 'input' ? sessionVersion : captureVersion

    const load = useCallback(async () => {
        setLoading(true)
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
        } finally {
            setLoading(false)
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

    const filtersActive = link !== '' || type !== '' || status !== ''
        || sort !== 'sequence' || dir !== 'asc'

    const resetFilters = () => changeFilter(() => {
        setLink('')
        setType('')
        setStatus('')
        setSort('sequence')
        setDir('asc')
    })

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

    /**
     * Keeps the message about to go out in view, and turns the page the moment
     * the run crosses the end of this one.
     *
     * <p>Three things make this harder than a scroll. The list is paged, so at
     * 500 rows a page a three-minute run leaves the operator watching a page the
     * run left thirty seconds ago. The page in state and the page on screen are
     * not the same thing between asking for one and it arriving, so every
     * decision here reads {@code page.offset} -- what is actually loaded --
     * rather than the offset we last asked for. And the row worth watching is
     * the first one still pending, not the last one sent: at a boundary the last
     * sent is the final row of this page while the next to go is the first row
     * of the next, and following the sent one would sit still exactly when the
     * handover happens.
     */
    const turning = useRef(false)
    useEffect(() => {
        if (mode !== 'input' || !followRun || !running || !page) {
            turning.current = false
            return
        }
        const loaded = page.offset
        // A page we asked for but have not been given yet: decide nothing on it.
        if (turning.current && loaded !== offset) return
        turning.current = false

        const rows = page.items as MessageSummary[]
        if (rows.length === 0) return

        const pending = rows.findIndex((row) => !row.sent)

        // Every row here has gone out. The run is on the next page now.
        if (pending < 0) {
            if (loaded + PAGE < page.filtered) {
                turning.current = true
                setOffset(loaded + PAGE)
            }
            return
        }

        const element = scrollRef.current
        if (!element) return
        // Held a third of the way down rather than pinned to an edge, so the rows
        // just sent and the rows about to go are both in view. After a page turn
        // this resolves to the top on its own, which is where the run continues.
        const target = Math.max(0, pending * ROW_HEIGHT - element.clientHeight / 3)
        const distance = Math.abs(element.scrollTop - target)
        if (distance > ROW_HEIGHT / 2) {
            element.scrollTo({
                top: target,
                behavior: distance > element.clientHeight ? 'auto' : 'smooth',
            })
        }
    }, [mode, followRun, running, page, offset])

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

    /**
     * Reordering only means anything in file order. Under any other sort the
     * row above is not the message that goes out before this one, so dragging
     * would be asking the operator to arrange a list that is not the list.
     */
    const reorderable = mode === 'input' && sort === 'sequence' && dir === 'asc'
        && !filtersActive && !running

    const indexOfId = (id: number) => items.findIndex((row) => row.id === id)

    const moveTo = async (id: number, toIndexInPage: number) => {
        const result = await run(
            () => api.moveMessage(id, offset + toIndexInPage), t('list.moved', { index: toIndexInPage + 1 }))
        if (result) {
            touchSession()
            notify('INFO', t('list.moved', { index: count(result.index + 1) }))
        }
    }

    const copySelected = async () => {
        if (!selection || selection.mode !== 'input') return
        const detail = await run(() => api.sessionMessage(selection.id))
        if (!detail?.type) return
        clipboard.current = { type: detail.type, payload: detail.payload ?? {} }
        notify('INFO', t('list.copied', { type: detail.type }))
    }

    const pasteAfterSelected = async () => {
        const held = clipboard.current
        if (!held) {
            notify('WARN', t('list.pasteEmpty'))
            return
        }
        const at = selection ? indexOfId(selection.id) : -1
        const index = at >= 0 ? offset + at + 1 : (page?.total ?? 0)
        const inserted = await run(() => api.insertMessage({
            type: held.type, index, offsetMillis: PASTE_OFFSET_MS, payload: held.payload,
        }), t('list.pasted', { type: held.type, index: count(index + 1), offset: PASTE_OFFSET_MS }))
        if (inserted) {
            touchSession()
            onSelect({ mode: 'input', id: inserted.id })
            notify('INFO', t('list.pasted', {
                type: held.type, index: count(index + 1), offset: PASTE_OFFSET_MS,
            }))
        }
    }

    const confirmDelete = async () => {
        const target = askDelete
        if (!target) return
        const done = await run(() => api.deleteMessage(target.id), t('dialog.deleteConfirm'))
        setAskDelete(null)
        if (done) {
            touchSession()
            if (selection?.id === target.id) onSelect(null)
        }
    }

    const step = async (direction: 'undo' | 'redo') => {
        const done = await run(
            () => (direction === 'undo' ? api.undoSession() : api.redoSession()),
            direction === 'undo' ? t('list.undo') : t('list.redo'))
        if (done?.applied) {
            touchSession()
            notify('info', t(direction === 'undo' ? 'list.undone' : 'list.redone',
                { what: done.label ?? '' }))
        }
    }

    const confirmRevert = async () => {
        const done = await run(() => api.revertSession(), t('list.revert'))
        setAskRevert(false)
        if (done) {
            touchSession()
            onSelect(null)
            notify('INFO', t('list.reverted', { name: page?.source ?? '' }))
        }
    }

    const confirmClear = async () => {
        const done = await run(() => api.clearSession(), t('list.clear'))
        setAskClear(false)
        if (done) {
            touchSession()
            onSelect(null)
            notify('INFO', t('list.cleared'))
        }
    }

    // Keyboard shortcuts on the list. Ignored while a field has focus, so typing
    // a value into the editor never deletes the message being edited.
    useEffect(() => {
        if (mode !== 'input') return
        const onKey = (event: KeyboardEvent) => {
            const target = event.target as HTMLElement | null
            if (target && (target.isContentEditable
                || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName))) {
                return
            }
            const accel = event.ctrlKey || event.metaKey
            if (accel && event.key.toLowerCase() === 'c') {
                if (!selection || selection.mode !== 'input') return
                event.preventDefault()
                void copySelected()
            } else if (accel && event.key.toLowerCase() === 'v') {
                if (running) return
                event.preventDefault()
                void pasteAfterSelected()
            } else if (event.key === 'Delete' || event.key === 'Backspace') {
                if (running || !selection || selection.mode !== 'input') return
                const row = items.find((r) => r.id === selection.id) as MessageSummary | undefined
                if (!row || row.sent) return
                event.preventDefault()
                setAskDelete(row)
            }
        }
        window.addEventListener('keydown', onKey)
        return () => window.removeEventListener('keydown', onKey)
    })

    const pages = page ? Math.max(1, Math.ceil(page.filtered / PAGE)) : 1
    const current = Math.floor(offset / PAGE)

    const deleteIndex = askDelete ? indexOfId(askDelete.id) + 1 + offset : 0

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
                <span className="flex items-center gap-2 normal-case tracking-normal text-ink-400">
                    {loading && <LoadingSpinner size={12} />}
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

            {/* Two rows, split by what they touch. The first changes how the list
                reads; the second changes the set itself. Mixing them put five
                controls on one line that wrapped raggedly and gave a filter
                reset the same weight as deleting everything. */}
            {mode !== 'trace' && (
                <>
                    <div className="flex items-center gap-3 px-2 py-1.5 border-b border-ink-800 flex-wrap text-mini">
                        <Segmented<TimeMode>
                            value={timeMode}
                            onChange={setTimeMode}
                            options={[
                                { value: 'cumulative', label: t('list.timeCumulative'), title: t('list.timeTitle') },
                                { value: 'delta', label: t('list.timeDelta'), title: t('list.timeTitle') },
                            ]}
                        />
                        {mode === 'input' && (page?.total ?? 0) > 0 && (
                            <Toggle
                                checked={followRun}
                                onChange={setFollowRun}
                                label={t('list.followRun')}
                                title={t('list.followRunTitle')}
                            />
                        )}
                    </div>

                    <div className="flex items-center gap-1.5 px-2 py-1.5 border-b border-ink-700 flex-wrap text-mini">
                        {mode === 'input' && (
                            <span className="inline-flex">
                                <button
                                    className="btn py-0.5 px-2 text-mini"
                                    onClick={() => void step('undo')}
                                    disabled={running || !page?.canUndo}
                                    title={page?.undoLabel
                                        ? t('list.undoWhat', { what: page.undoLabel })
                                        : t('list.undoTitle')}
                                    aria-label={t('list.undo')}
                                >
                                    <Icon name="undo" size={13} />
                                </button>
                                <button
                                    className="btn py-0.5 px-2 text-mini border-l-0"
                                    onClick={() => void step('redo')}
                                    disabled={running || !page?.canRedo}
                                    title={page?.redoLabel
                                        ? t('list.redoWhat', { what: page.redoLabel })
                                        : t('list.redoTitle')}
                                    aria-label={t('list.redo')}
                                >
                                    <Icon name="redo" size={13} />
                                </button>
                            </span>
                        )}

                        <button
                            className="btn py-0.5 text-mini"
                            onClick={resetFilters}
                            disabled={!filtersActive}
                            title={t('list.resetTitle')}
                        >
                            <Icon name="filterOff" size={13} />
                            {t('list.reset')}
                        </button>

                        {mode === 'input' && (
                            <button
                                className="btn py-0.5 text-mini"
                                onClick={() => setAskRevert(true)}
                                disabled={running || !page?.revertable || !page?.dirty}
                                title={t('list.revertTitle')}
                            >
                                <Icon name="revert" size={13} />
                                {t('list.revert')}
                            </button>
                        )}

                        <span className="flex-1" />

                        {mode === 'input' && (
                            <button
                                className="btn btn-danger py-0.5 text-mini"
                                onClick={() => setAskClear(true)}
                                disabled={running || (page?.total ?? 0) === 0}
                                title={t('list.clearTitle')}
                            >
                                <Icon name="trash" size={13} />
                                {t('list.clear')}
                            </button>
                        )}
                    </div>
                </>
            )}

            {mode === 'input' && reorderable && (page?.total ?? 0) > 1 && (
                <div className="px-2 py-1 border-b border-ink-700 text-micro text-ink-500">
                    {t('list.reorderHint')}
                </div>
            )}

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
                <LoadingOverlay show={loading && items.length === 0} />
                <div style={{ height: items.length * ROW_HEIGHT }} className="relative">
                    {visible.map((item, index) => {
                        const absolute = first + index
                        if (mode === 'trace') {
                            return (
                                <TraceRowView
                                    key={`${(item as TraceRow).direction}-${item.id}`}
                                    t={t}
                                    row={item as TraceRow}
                                    top={absolute * ROW_HEIGHT}
                                    selected={selection?.id === item.id}
                                    onClick={() => onSelect({
                                        mode: (item as TraceRow).direction === 'IN' ? 'output' : 'input',
                                        id: item.id,
                                    })}
                                />
                            )
                        }
                        const row = item as MessageSummary
                        const previous = absolute > 0 ? (items[absolute - 1] as MessageSummary) : null
                        return (
                            <Row
                                key={row.id}
                                t={t}
                                item={row}
                                shownTime={timeMode === 'delta' && previous
                                    ? `+${row.timestamp - previous.timestamp}`
                                    : String(row.timestamp)}
                                top={absolute * ROW_HEIGHT}
                                selected={selection?.mode === mode && selection.id === row.id}
                                draggable={reorderable && !row.sent}
                                dragging={dragId === row.id}
                                dropBefore={dropIndex === absolute}
                                onClick={() => onSelect({
                                    mode: mode as 'input' | 'output', id: row.id, index: offset + absolute,
                                })}
                                onDragStart={() => setDragId(row.id)}
                                onDragOver={() => setDropIndex(absolute)}
                                onDragEnd={() => { setDragId(null); setDropIndex(null) }}
                                onDrop={(draggedId) => {
                                    setDragId(null)
                                    setDropIndex(null)
                                    if (draggedId !== row.id) void moveTo(draggedId, absolute)
                                }}
                            />
                        )
                    })}
                </div>
                {items.length === 0 && !loading && (
                    <div className="p-4 text-ink-500 text-center">
                        {mode === 'input' ? t('list.emptyStimulus')
                            : mode === 'output' ? t('list.emptyCapture')
                                : t('list.emptyTrace')}
                    </div>
                )}
            </div>

            {mode !== 'trace' && page && page.filtered > PAGE && (
                <Pagination
                    t={t}
                    pages={pages}
                    current={current}
                    from={page.offset + 1}
                    to={Math.min(page.offset + PAGE, page.filtered)}
                    total={page.filtered}
                    onGo={(index) => {
                        setOffset(index * PAGE)
                        if (scrollRef.current) scrollRef.current.scrollTop = 0
                    }}
                />
            )}

            <AlertDialog
                open={askRevert}
                tone="caution"
                title={t('dialog.revertTitle')}
                body={t('dialog.revertBody', { name: page?.source ?? '' })}
                detail={t('dialog.revertDetail')}
                confirmLabel={t('dialog.revertConfirm')}
                onConfirm={() => void confirmRevert()}
                onCancel={() => setAskRevert(false)}
            />

            <AlertDialog
                open={askClear}
                title={t('dialog.clearTitle')}
                body={t('dialog.clearBody')}
                detail={page?.dirty ? t('dialog.clearDirty') : null}
                confirmLabel={t('dialog.clearConfirm')}
                onConfirm={() => void confirmClear()}
                onCancel={() => setAskClear(false)}
            />

            <AlertDialog
                open={askDelete !== null}
                title={t('dialog.deleteTitle')}
                body={t('dialog.deleteBody', {
                    index: count(deleteIndex),
                    type: askDelete?.type ?? `msg_id ${askDelete?.msgId ?? ''}`,
                })}
                confirmLabel={t('dialog.deleteConfirm')}
                onConfirm={() => void confirmDelete()}
                onCancel={() => setAskDelete(null)}
            />
        </div>
    )
}

/**
 * Numbered pages with the ends always reachable.
 *
 * <p>Previous/next alone makes the far end of a long capture a hundred clicks
 * away; a full run of numbers makes the control wider than the panel. Five
 * around the current position, plus both ends, fits and gets anywhere in two
 * moves.
 */
function Pagination({ t, pages, current, from, to, total, onGo }: {
    t: Translate
    pages: number
    current: number
    /** First and last row numbers on this page, and how many rows there are in all. */
    from: number
    to: number
    total: number
    onGo: (index: number) => void
}) {
    const window: number[] = []
    for (let i = Math.max(0, current - 2); i <= Math.min(pages - 1, current + 2); i++) {
        window.push(i)
    }
    const gapBefore = window[0] > 1
    const gapAfter = window[window.length - 1] < pages - 2

    const button = (index: number) => (
        <button
            key={index}
            className={`btn py-0.5 px-2 text-mini ${index === current ? 'btn-primary' : ''}`}
            aria-current={index === current ? 'page' : undefined}
            onClick={() => onGo(index)}
        >
            {index + 1}
        </button>
    )

    return (
        <div className="border-t border-ink-700 shrink-0">
            <div className="flex items-center gap-1 px-2 pt-1.5 text-mini flex-wrap">
                <button className="btn py-0.5 px-2 text-mini" disabled={current === 0}
                    onClick={() => onGo(current - 1)}>{t('list.previous')}</button>

                {window[0] > 0 && button(0)}
                {gapBefore && <span className="text-ink-600 px-0.5">…</span>}
                {window.map(button)}
                {gapAfter && <span className="text-ink-600 px-0.5">…</span>}
                {window[window.length - 1] < pages - 1 && (
                    <button
                        className={`btn py-0.5 px-2 text-mini ${current === pages - 1 ? 'btn-primary' : ''}`}
                        onClick={() => onGo(pages - 1)}
                        title={t('list.last')}
                    >
                        {t('list.last')}
                    </button>
                )}

                <span className="flex-1" />
                <button className="btn py-0.5 px-2 text-mini" disabled={current >= pages - 1}
                    onClick={() => onGo(current + 1)}>{t('list.next')}</button>
            </div>

            {/* Which rows these are, in the numbering of the whole filtered set.
                "Page 5 of 28" alone leaves the reader counting pages to work out
                where they are in thirteen thousand messages. */}
            <div className="flex items-center gap-2 px-2 pb-1.5 pt-1 text-micro text-ink-500">
                <span className="num">
                    {t('list.showing', {
                        from: count(from), to: count(to), total: count(total),
                    })}
                </span>
                <span className="flex-1" />
                <span>{t('list.pageOf', { page: current + 1, pages })}</span>
            </div>
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
            className={`px-2 py-0.5  text-micro uppercase tracking-[0.14em] transition-colors ${active ? 'bg-signal-dim/30 text-signal' : 'text-ink-400 hover:text-ink-200'
                }`}
        >
            {children}
        </button>
    )
}

function Row({
    t, item, shownTime, top, selected, draggable, dragging, dropBefore,
    onClick, onDragStart, onDragOver, onDragEnd, onDrop,
}: {
    t: Translate
    item: MessageSummary
    shownTime: string
    top: number
    selected: boolean
    draggable: boolean
    dragging: boolean
    dropBefore: boolean
    onClick: () => void
    onDragStart: () => void
    onDragOver: () => void
    onDragEnd: () => void
    /** The id being dragged, read from the drag payload rather than from state. */
    onDrop: (draggedId: number) => void
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
            draggable={draggable}
            onDragStart={(event) => {
                event.dataTransfer.effectAllowed = 'move'
                // Firefox refuses to start a drag without payload on the transfer.
                event.dataTransfer.setData('text/plain', String(item.id))
                onDragStart()
            }}
            onDragOver={(event) => {
                if (!draggable) return
                event.preventDefault()
                event.dataTransfer.dropEffect = 'move'
                onDragOver()
            }}
            onDragEnd={onDragEnd}
            onDrop={(event) => {
                event.preventDefault()
                // The dragged id travels on the transfer, not in React state: the
                // drop handler bound to this row was created before the drag
                // began, so a state read here would see whatever was there then.
                const dragged = Number(event.dataTransfer.getData('text/plain'))
                if (Number.isFinite(dragged) && dragged > 0) onDrop(dragged)
            }}
            style={{ top, height: ROW_HEIGHT }}
            className={`group absolute left-0 right-0 flex items-center gap-2 pr-2 text-left border-l-2
                transition-[background-color,opacity] duration-100
                ${dragging ? 'opacity-35' : ''}
                ${dropBefore ? 'before:absolute before:left-0 before:right-0 before:-top-px before:h-0.5 before:bg-signal before:content-[\'\']' : ''}
                ${selected
                    ? 'bg-signal-dim/25 border-l-signal'
                    : `border-l-transparent hover:bg-ink-800 ${item.sent && !item.problem ? 'bg-ink-950/50' : ''}`
                }`}
            title={item.problem ?? item.preview}
        >
            {/* A grip, shown only where dragging does something. Two dotted rules
                rather than an icon font: it reads as a handle at 26px and costs
                nothing. */}
            <span
                aria-hidden="true"
                className={`w-3 shrink-0 self-stretch flex items-center justify-center
                    ${draggable ? 'cursor-grab active:cursor-grabbing' : ''}`}
            >
                {draggable && (
                    <Icon name="grip" size={11}
                        className="text-ink-600 opacity-0 group-hover:opacity-100 transition-opacity" />
                )}
            </span>
            <span className="w-20 shrink-0 truncate text-ink-500 tabular-nums">{shownTime}</span>
            <span className="w-10 shrink-0 text-ink-400">{item.link ?? '?'}</span>
            <span className={`w-40 shrink-0 truncate ${tone}`}>
                {item.type ?? `msg_id ${item.msgId}`}
            </span>
            <span className="flex-1 truncate text-ink-500">{item.preview}</span>
            <span className="w-[76px] shrink-0 flex justify-end">
                {item.problem
                    ? <span className="badge badge-blocked">{t('row.blocked')}</span>
                    : item.direction === 'FROM_DKM'
                        ? <span className="badge badge-capture">{t('inspector.fromDkm')}</span>
                        : item.sent
                            ? <span className="badge badge-sent">{t('row.sent')}</span>
                            : <span className="badge badge-pending">{t('row.pending')}</span>}
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
