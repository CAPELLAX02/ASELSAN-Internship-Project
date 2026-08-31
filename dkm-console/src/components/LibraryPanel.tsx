import { useCallback, useEffect, useState } from 'react'

import { api } from '../api/client'
import type { LibraryItem } from '../api/types'
import { useT } from '../i18n/useT'
import { useStore } from '../store/useStore'
import { AlertDialog } from './AlertDialog'
import { LoadingSpinner } from './LoadingSpinner'
import type { Selection } from './MessagePanel'
import { Segmented } from './Switch'

/**
 * The reusable message library (FR-23, FR-24).
 *
 * An entry saved against a different interface version is marked, and
 * inserting it takes a second, deliberate confirmation. Its bytes were correct
 * for a layout that no longer exists, so sending it silently would put a
 * message on the wire that means something other than what the operator reads
 * on screen.
 *
 * <p>Insertion is placed the same way the new-message form places it: an entry
 * arriving from the library is still an insert, and having the two paths answer
 * "where does it go" differently would be the kind of inconsistency an operator
 * only discovers by getting it wrong.
 */
export function LibraryPanel({ selection }: { selection: Selection | null }) {
    const t = useT()
    const [query, setQuery] = useState('')
    const [items, setItems] = useState<LibraryItem[]>([])
    const [directory, setDirectory] = useState<string | null>(null)
    const [unavailable, setUnavailable] = useState<string | null>(null)
    const [busy, setBusy] = useState(false)
    const [loading, setLoading] = useState(true)

    const [placement, setPlacement] = useState<'after' | 'end'>('after')
    const [offsetMillis, setOffsetMillis] = useState(500)
    const [askStale, setAskStale] = useState<LibraryItem | null>(null)
    const [askDelete, setAskDelete] = useState<LibraryItem | null>(null)

    const sessionCount = useStore((s) => s.sessionCount)
    const running = useStore((s) => s.playback.state === 'RUNNING')
    const run = useStore((s) => s.run)
    const notify = useStore((s) => s.notify)
    const touchSession = useStore((s) => s.touchSession)

    const selectedIndex = selection?.mode === 'input' && selection.index !== undefined
        ? selection.index
        : null
    const canPlaceAfter = selectedIndex !== null
    const index = placement === 'after' && canPlaceAfter ? selectedIndex + 1 : sessionCount

    const refresh = useCallback(async () => {
        setLoading(true)
        const result = await run(() => api.library(query), t('inspector.library'))
        setLoading(false)
        if (result) {
            setItems(result.items)
            setDirectory(result.directory)
            setUnavailable(result.available ? null : result.reason)
        }
    }, [query, run, t])

    useEffect(() => {
        const timer = window.setTimeout(() => void refresh(), 150)
        return () => window.clearTimeout(timer)
    }, [refresh])

    const doInsert = async (item: LibraryItem) => {
        setBusy(true)
        const inserted = await run(
            () => api.insertFromLibrary(item.id, {
                index, offsetMillis, force: item.stale,
            }),
            t('library.insert'))
        setBusy(false)
        if (inserted) {
            touchSession()
            notify(item.stale ? 'WARN' : 'INFO', item.stale
                ? t('library.insertedStale', { name: item.name, timestamp: inserted.timestamp })
                : t('library.inserted', { name: item.name, timestamp: inserted.timestamp }))
        }
    }

    const insert = (item: LibraryItem) => {
        if (item.stale) {
            setAskStale(item)
            return
        }
        void doInsert(item)
    }

    const confirmDelete = async () => {
        const item = askDelete
        if (!item) return
        setBusy(true)
        await run(() => api.deleteLibraryItem(item.id), t('library.delete'))
        setBusy(false)
        setAskDelete(null)
        void refresh()
    }

    return (
        <div className="flex flex-col gap-3">
            <input
                className="field"
                placeholder={t('library.search')}
                value={query}
                disabled={Boolean(unavailable)}
                onChange={(e) => setQuery(e.target.value)}
            />

            {!unavailable && (
                <div className="flex flex-col gap-2 border border-ink-700 bg-ink-850/50 px-2.5 py-2">
                    <div className="flex items-start gap-2">
                        <span className="w-24 shrink-0 text-ink-400 pt-0.5">{t('new.placement')}</span>
                        <span className="flex-1 min-w-0">
                            <Segmented<'after' | 'end'>
                                value={canPlaceAfter ? placement : 'end'}
                                onChange={setPlacement}
                                options={[
                                    { value: 'after', label: t('new.placeAfter'), title: t('new.placeAfterTitle') },
                                    { value: 'end', label: t('new.placeEnd'), title: t('new.placeEndTitle') },
                                ]}
                            />
                            {!canPlaceAfter && (
                                <span className="block text-ink-500 mt-1">{t('new.noSelection')}</span>
                            )}
                        </span>
                    </div>
                    <label className="flex items-center gap-2">
                        <span className="w-24 shrink-0 text-ink-400">{t('new.offset')}</span>
                        <input className="field py-0.5" type="number" value={offsetMillis}
                            onChange={(e) => setOffsetMillis(Number(e.target.value))} />
                    </label>
                </div>
            )}

            {unavailable && (
                <div className="border border-caution/50 bg-caution/10 text-caution px-2 py-1.5">
                    {unavailable}
                </div>
            )}

            {loading && items.length === 0 && !unavailable && (
                <div className="flex items-center gap-2 text-ink-500 py-2">
                    <LoadingSpinner size={12} /> {t('app.loading')}
                </div>
            )}

            {!loading && items.length === 0 && !unavailable && (
                <div className="text-ink-500">{t('library.empty')}</div>
            )}

            <div className="flex flex-col gap-1.5">
                {items.map((item) => (
                    <div key={item.id}
                        className={`border px-2.5 py-2 ${item.stale
                            ? 'border-caution/50 bg-caution/5'
                            : 'border-ink-700 bg-ink-850/60'}`}>
                        <div className="flex items-baseline gap-2">
                            <span className="text-ink-100 truncate">{item.name}</span>
                            {item.stale && (
                                <span className="badge badge-pending"
                                    title={t('library.staleTitle', { version: item.schemaVersion })}>
                                    {t('library.stale')}
                                </span>
                            )}
                            <div className="flex-1" />
                            <button className="btn py-0 text-mini" disabled={busy || running}
                                onClick={() => insert(item)}>{t('library.insert')}</button>
                            <button className="btn btn-danger py-0 text-mini" disabled={busy}
                                onClick={() => setAskDelete(item)}>{t('library.delete')}</button>
                        </div>
                        <div className="text-ink-500 mt-0.5">
                            {item.typeName} &middot; {item.length} B &middot; interface {item.schemaVersion}
                            {item.tags.length > 0 && <> &middot; {item.tags.join(', ')}</>}
                        </div>
                        {item.description && <div className="text-ink-400 mt-0.5">{item.description}</div>}
                    </div>
                ))}
            </div>

            {directory && (
                <div className="text-ink-600 text-micro pt-1 border-t border-ink-800 break-all">
                    {t('library.stored', { directory })}
                </div>
            )}
            {running && <div className="text-caution">{t('library.pauseFirst')}</div>}

            <AlertDialog
                open={askStale !== null}
                tone="caution"
                title={t('library.stale')}
                body={t('library.staleConfirm', {
                    name: askStale?.name ?? '', version: askStale?.schemaVersion ?? '',
                })}
                confirmLabel={t('library.insert')}
                busy={busy}
                onConfirm={() => { const item = askStale; setAskStale(null); if (item) void doInsert(item) }}
                onCancel={() => setAskStale(null)}
            />

            <AlertDialog
                open={askDelete !== null}
                title={t('library.delete')}
                body={t('library.deleteConfirm', { name: askDelete?.name ?? '' })}
                confirmLabel={t('library.delete')}
                busy={busy}
                onConfirm={() => void confirmDelete()}
                onCancel={() => setAskDelete(null)}
            />

        </div>
    )
}
