import { useCallback, useEffect, useMemo, useState } from 'react'

import { api } from '../api/client'
import type { MessageDetail } from '../api/types'
import { useT, type Translate } from '../i18n/useT'
import { useStore } from '../store/useStore'
import { FieldEditor, type FieldPath } from './FieldEditor'
import { LibraryPanel } from './LibraryPanel'
import { clockTime } from './format'
import type { Selection } from './MessagePanel'

type Tab = 'message' | 'new' | 'library'

/** Immutable set-at-path, so a draft edit never mutates what came back from the server. */
function setIn<T>(source: T, path: FieldPath, value: unknown): T {
    if (path.length === 0) return value as T
    const [head, ...rest] = path
    if (typeof head === 'number') {
        const array = Array.isArray(source) ? [...(source as unknown[])] : []
        array[head] = setIn(array[head], rest, value)
        return array as unknown as T
    }
    const object = { ...((source as Record<string, unknown>) ?? {}) }
    object[head] = setIn(object[head], rest, value)
    return object as T
}

export function InspectorPanel({ selection, onSelect }: {
    selection: Selection | null
    onSelect: (selection: Selection | null) => void
}) {
    const t = useT()

    // Picking a message in the list should bring its fields into view, but a tab
    // the operator chose deliberately should survive until they pick a different
    // message. Both fall out of remembering which selection the choice was made
    // for — no effect, and no frame rendered on the wrong tab.
    const [chosenTab, setChosenTab] = useState<{ tab: Tab; forSelection: string }>({
        tab: 'message', forSelection: '',
    })
    const selectionKey = selection ? `${selection.mode}:${selection.id}` : ''
    const tab = chosenTab.forSelection === selectionKey ? chosenTab.tab : 'message'
    const setTab = (next: Tab) => setChosenTab({ tab: next, forSelection: selectionKey })

    return (
        <div className="panel min-h-0 flex-1" data-tour="inspector">
            <div className="panel-title">
                <span className="flex items-center gap-1">
                    <TabButton active={tab === 'message'} onClick={() => setTab('message')}>
                        {t('inspector.message')}
                    </TabButton>
                    <TabButton active={tab === 'new'} onClick={() => setTab('new')}>
                        {t('inspector.new')}
                    </TabButton>
                    <TabButton active={tab === 'library'} onClick={() => setTab('library')}>
                        {t('inspector.library')}
                    </TabButton>
                </span>
            </div>
            <div className="flex-1 overflow-auto min-h-0 p-3">
                {tab === 'message' && <MessageInspector selection={selection} onSelect={onSelect} />}
                {tab === 'new' && <NewMessageForm />}
                {tab === 'library' && <LibraryPanel />}
            </div>
        </div>
    )
}

function TabButton({ active, onClick, children }: {
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

function MessageInspector({ selection, onSelect }: {
    selection: Selection | null
    onSelect: (selection: Selection | null) => void
}) {
    const t = useT()
    const schema = useStore((s) => s.schema)
    const playbackState = useStore((s) => s.playback.state)
    const run = useStore((s) => s.run)
    const notify = useStore((s) => s.notify)
    const touchSession = useStore((s) => s.touchSession)
    const sessionVersion = useStore((s) => s.sessionVersion)

    const [detail, setDetail] = useState<MessageDetail | null>(null)
    const [draft, setDraft] = useState<Record<string, unknown> | null>(null)
    const [busy, setBusy] = useState(false)
    const [loadError, setLoadError] = useState<string | null>(null)

    const load = useCallback(async () => {
        if (!selection) {
            setDetail(null)
            setDraft(null)
            return
        }
        try {
            const result = selection.mode === 'input'
                ? await api.sessionMessage(selection.id)
                : await api.captureMessage(selection.id)
            setDetail(result)
            setDraft((result.payload ?? {}) as Record<string, unknown>)
            setLoadError(null)
        } catch (error) {
            setLoadError((error as Error).message)
            setDetail(null)
        }
    }, [selection])

    // Synchronising with the gateway: the state lands asynchronously, which is
    // exactly the case an effect is for.
    useEffect(() => { void load() }, [load, sessionVersion])

    const type = useMemo(
        () => schema?.messages.find((m) => m.qualifiedName === detail?.type) ?? null,
        [schema, detail],
    )

    if (loadError) {
        return <div className="text-danger">{loadError}</div>
    }
    if (!selection || !detail) {
        return <div className="text-ink-500">{t('inspector.selectHint')}</div>
    }

    const isCapture = selection.mode === 'output'
    const running = playbackState === 'RUNNING'
    const readOnly = isCapture || detail.sent || !detail.decodable || running

    const readOnlyReason = isCapture
        ? t('inspector.readOnlyCapture')
        : !detail.decodable
            ? detail.problem ?? t('inspector.readOnlyUndecodable')
            : detail.sent
                ? t('inspector.readOnlySent')
                : running
                    ? t('inspector.readOnlyRunning')
                    : null

    const dirty = draft !== null && JSON.stringify(draft) !== JSON.stringify(detail.payload ?? {})

    const apply = async () => {
        if (!draft) return
        setBusy(true)
        const updated = await run(() => api.editMessage(detail.id, draft), t('inspector.apply'))
        setBusy(false)
        if (updated) {
            setDetail(updated)
            setDraft((updated.payload ?? {}) as Record<string, unknown>)
            touchSession()
            notify('INFO', t('inspector.updated', { type: updated.type ?? '', id: updated.id }))
        }
    }

    const remove = async () => {
        setBusy(true)
        const result = await run(() => api.deleteMessage(detail.id), t('inspector.delete'))
        setBusy(false)
        if (result?.deleted) {
            onSelect(null)
            touchSession()
        }
    }

    const saveToLibrary = async () => {
        const name = window.prompt(t('inspector.savePrompt'), `${detail.type} #${detail.id}`)
        if (!name) return
        setBusy(true)
        const saved = await run(() => api.saveToLibrary(detail.id, { name }), t('inspector.toLibrary'))
        setBusy(false)
        if (saved) notify('INFO', t('inspector.saved', { name: saved.name }))
    }

    const retime = async () => {
        const next = window.prompt(t('inspector.retimePrompt'), String(detail.timestamp))
        if (next === null) return
        const value = Number(next)
        if (!Number.isFinite(value)) return
        setBusy(true)
        const updated = await run(() => api.retimeMessage(detail.id, value), t('inspector.retime'))
        setBusy(false)
        if (updated) {
            setDetail(updated)
            touchSession()
        }
    }

    return (
        <div className="flex flex-col gap-3">
            <div>
                <div className="flex items-baseline gap-2 flex-wrap">
                    <span className="text-ink-100 text-body">{detail.type ?? `msg_id ${detail.msgId}`}</span>
                    <span className="text-ink-500">#{detail.id}</span>
                    <span className={`chip ${isCapture
                        ? 'border-signal/50 text-signal bg-signal/10'
                        : detail.sent ? 'border-ink-600 text-ink-400' : 'border-good/50 text-good bg-good/10'}`}>
                        {isCapture ? t('inspector.fromDkm') : detail.sent ? t('row.sent') : t('row.pending')}
                    </span>
                    {detail.origin && detail.origin !== 'FILE' && (
                        <span className="text-ink-500 text-micro">{detail.origin.toLowerCase()}</span>
                    )}
                </div>
                {type?.doc && <div className="text-ink-500 mt-1">{type.doc}</div>}
                <div className="text-ink-500 mt-1">
                    {t('inspector.link', { link: detail.link ?? '?' })}
                    {' · '}{t('inspector.bytes', { count: detail.length })}
                    {' · '}t={detail.timestamp} ms
                    {detail.wallClock > 0 && (
                        <> {' · '}{isCapture
                            ? t('inspector.receivedAt', { time: clockTime(detail.wallClock) })
                            : t('inspector.sentAt', { time: clockTime(detail.wallClock) })}</>
                    )}
                </div>
            </div>

            {detail.problem && (
                <div className="rounded border border-danger/50 bg-danger/10 text-danger px-2 py-1.5">
                    {detail.problem}
                </div>
            )}

            {readOnlyReason && !detail.problem && (
                <div className="rounded border border-ink-600 bg-ink-850 text-ink-400 px-2 py-1.5">
                    {readOnlyReason}
                </div>
            )}

            {schema && type && detail.decodable && draft && (
                <FieldEditor
                    schema={schema}
                    type={type}
                    value={draft}
                    readOnly={readOnly}
                    onChange={(path, next) => setDraft((current) => setIn(current ?? {}, path, next))}
                />
            )}

            {!detail.decodable && <HeaderTable header={detail.header} />}

            <div className="flex items-center gap-1.5 flex-wrap pt-1 border-t border-ink-700">
                <button className="btn btn-primary" disabled={readOnly || !dirty || busy} onClick={apply}>
                    {t('inspector.apply')}
                </button>
                <button className="btn" disabled={!dirty || busy}
                    onClick={() => setDraft((detail.payload ?? {}) as Record<string, unknown>)}>
                    {t('inspector.revert')}
                </button>
                <div className="flex-1" />
                {!isCapture && (
                    <>
                        <button className="btn" disabled={readOnly || busy} onClick={retime}
                            title={t('inspector.retimeTitle')}>
                            {t('inspector.retime')}
                        </button>
                        <button className="btn" disabled={!detail.decodable || busy} onClick={saveToLibrary}
                            title={t('inspector.toLibraryTitle')}>
                            {t('inspector.toLibrary')}
                        </button>
                        <button className="btn btn-danger" disabled={detail.sent || running || busy} onClick={remove}>
                            {t('inspector.delete')}
                        </button>
                    </>
                )}
            </div>

            <details className="text-ink-500">
                <summary className="cursor-pointer select-none">{t('inspector.wireHeader')}</summary>
                <HeaderTable header={detail.header} />
            </details>
        </div>
    )
}

function HeaderTable({ header }: { header: Record<string, number> }) {
    return (
        <table className="w-full mt-1 text-ink-400">
            <tbody>
                {Object.entries(header ?? {}).map(([name, value]) => (
                    <tr key={name} className="border-b border-ink-800 last:border-0">
                        <td className="py-0.5 pr-3 text-ink-500">{name}</td>
                        <td className="py-0.5 text-ink-200 tabular-nums">{String(value)}</td>
                    </tr>
                ))}
            </tbody>
        </table>
    )
}

/** FR-9: a new message goes in at a chosen position with an explicit timing offset. */
function NewMessageForm() {
    const t: Translate = useT()
    const schema = useStore((s) => s.schema)
    const sessionCount = useStore((s) => s.sessionCount)
    const running = useStore((s) => s.playback.state === 'RUNNING')
    const run = useStore((s) => s.run)
    const notify = useStore((s) => s.notify)
    const touchSession = useStore((s) => s.touchSession)

    const stimulusTypes = useMemo(
        () => schema?.messages.filter((m) => m.direction !== 'FROM_DKM') ?? [],
        [schema],
    )

    // Both of these default to something derived rather than being pushed into
    // state by an effect. Pushing sessionCount into `index` on every change would
    // silently overwrite a position the operator had just typed.
    const [chosenType, setChosenType] = useState<string | null>(null)
    const [chosenIndex, setChosenIndex] = useState<number | null>(null)
    const [offsetMillis, setOffsetMillis] = useState(500)
    const [draft, setDraft] = useState<Record<string, unknown>>({})
    const [busy, setBusy] = useState(false)

    const typeName = chosenType ?? stimulusTypes[0]?.qualifiedName ?? ''
    const index = chosenIndex ?? sessionCount

    useEffect(() => {
        if (!typeName) return
        void api.template(typeName).then((template) => setDraft(template.payload)).catch(() => setDraft({}))
    }, [typeName])

    const type = stimulusTypes.find((m) => m.qualifiedName === typeName) ?? null

    const insert = async () => {
        if (!type) return
        setBusy(true)
        const inserted = await run(
            () => api.insertMessage({ type: type.qualifiedName, index, offsetMillis, payload: draft }),
            t('new.insert'))
        setBusy(false)
        if (inserted) {
            touchSession()
            // Back to tracking the end of the list, so a run of inserts appends.
            setChosenIndex(null)
            notify('INFO', t('new.inserted', {
                type: inserted.type ?? '', index, timestamp: inserted.timestamp,
            }))
        }
    }

    return (
        <div className="flex flex-col gap-3">
            <label className="flex items-center gap-2">
                <span className="w-44 shrink-0 text-ink-300">{t('new.type')}</span>
                <select className="field" value={typeName} onChange={(e) => setChosenType(e.target.value)}>
                    {stimulusTypes.map((m) => (
                        <option key={m.qualifiedName} value={m.qualifiedName}>
                            {m.qualifiedName} (msg_id {m.msgId}, {m.size} B)
                        </option>
                    ))}
                </select>
            </label>

            <label className="flex items-center gap-2">
                <span className="w-44 shrink-0 text-ink-300">{t('new.index')}</span>
                <input className="field" type="number" min={0} max={sessionCount} value={index}
                    onChange={(e) => setChosenIndex(Number(e.target.value))} />
            </label>

            <label className="flex items-start gap-2">
                <span className="w-44 shrink-0 text-ink-300 pt-1">{t('new.offset')}</span>
                <span className="flex-1">
                    <input className="field" type="number" value={offsetMillis}
                        onChange={(e) => setOffsetMillis(Number(e.target.value))} />
                    <span className="block text-ink-500 mt-1">{t('new.offsetHint')}</span>
                </span>
            </label>

            {schema && type && (
                <div className="border-t border-ink-700 pt-3">
                    <FieldEditor schema={schema} type={type} value={draft} readOnly={running}
                        onChange={(path, next) => setDraft((current) => setIn(current, path, next))} />
                </div>
            )}

            <div className="flex items-center gap-2 pt-1 border-t border-ink-700">
                <button className="btn btn-primary" disabled={!type || busy || running} onClick={insert}>
                    {t('new.insert')}
                </button>
                {running && <span className="text-caution">{t('new.pauseFirst')}</span>}
            </div>
        </div>
    )
}
