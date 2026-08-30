import { useCallback, useEffect, useState } from 'react'

import { api } from '../api/client'
import type { LibraryItem } from '../api/types'
import { useT } from '../i18n/useT'
import { useStore } from '../store/useStore'

/**
 * The reusable message library (FR-23, FR-24).
 *
 * An entry saved against a different interface version is marked, and
 * inserting it takes a second, deliberate confirmation. Its bytes were correct
 * for a layout that no longer exists, so sending it silently would put a
 * message on the wire that means something other than what the operator reads
 * on screen.
 */
export function LibraryPanel() {
    const t = useT()
    const [query, setQuery] = useState('')
    const [items, setItems] = useState<LibraryItem[]>([])
    const [directory, setDirectory] = useState<string | null>(null)
    const [unavailable, setUnavailable] = useState<string | null>(null)
    const [busy, setBusy] = useState(false)

    const sessionCount = useStore((s) => s.sessionCount)
    const running = useStore((s) => s.playback.state === 'RUNNING')
    const run = useStore((s) => s.run)
    const notify = useStore((s) => s.notify)
    const touchSession = useStore((s) => s.touchSession)

    const refresh = useCallback(async () => {
        const result = await run(() => api.library(query), t('inspector.library'))
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

    const insert = async (item: LibraryItem) => {
        if (item.stale) {
            const proceed = window.confirm(
                t('library.staleConfirm', { name: item.name, version: item.schemaVersion }))
            if (!proceed) return
        }
        const offset = window.prompt(t('library.offsetPrompt'), '500')
        if (offset === null) return
        setBusy(true)
        const inserted = await run(
            () => api.insertFromLibrary(item.id, {
                index: sessionCount,
                offsetMillis: Number(offset) || 0,
                force: item.stale,
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

    const remove = async (item: LibraryItem) => {
        if (!window.confirm(t('library.deleteConfirm', { name: item.name }))) return
        setBusy(true)
        await run(() => api.deleteLibraryItem(item.id), t('library.delete'))
        setBusy(false)
        void refresh()
    }

    return (
        <div className="flex flex-col gap-2">
            <input
                className="field"
                placeholder={t('library.search')}
                value={query}
                disabled={Boolean(unavailable)}
                onChange={(e) => setQuery(e.target.value)}
            />

            {unavailable && (
                <div className="rounded border border-caution/50 bg-caution/10 text-caution px-2 py-1.5">
                    {unavailable}
                </div>
            )}

            {items.length === 0 && !unavailable && (
                <div className="text-ink-500">{t('library.empty')}</div>
            )}

            <div className="flex flex-col gap-1.5">
                {items.map((item) => (
                    <div key={item.id}
                        className={`rounded border px-2 py-1.5 ${item.stale ? 'border-caution/50 bg-caution/5' : 'border-ink-700 bg-ink-850/60'}`}>
                        <div className="flex items-baseline gap-2">
                            <span className="text-ink-100 truncate">{item.name}</span>
                            {item.stale && (
                                <span className="chip border-caution/60 text-caution"
                                    title={t('library.staleTitle', { version: item.schemaVersion })}>
                                    {t('library.stale')}
                                </span>
                            )}
                            <div className="flex-1" />
                            <button className="btn py-0 text-mini" disabled={busy || running}
                                onClick={() => insert(item)}>{t('library.insert')}</button>
                            <button className="btn btn-danger py-0 text-mini" disabled={busy}
                                onClick={() => remove(item)}>{t('library.delete')}</button>
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
                <div className="text-ink-600 text-micro pt-1 border-t border-ink-800">
                    {t('library.stored', { directory })}
                </div>
            )}
            {running && <div className="text-caution">{t('library.pauseFirst')}</div>}
        </div>
    )
}
