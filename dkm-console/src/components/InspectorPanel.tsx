import { useCallback, useEffect, useMemo, useState } from 'react'

import { api } from '../api/client'
import type { MessageDetail } from '../api/types'
import { useT, type Translate } from '../i18n/useT'
import { useStore } from '../store/useStore'
import { Icon } from './Icon'
import { NumberField } from './NumberField'
import { AlertDialog } from './AlertDialog'
import { FieldEditor, type FieldPath } from './FieldEditor'
import { LibraryPanel } from './LibraryPanel'
import { LoadingLine, LoadingSpinner } from './LoadingSpinner'
import { PromptDialog } from './PromptDialog'
import { Segmented } from './Switch'
import { usePlacementCheck, type Placement } from './usePlacementCheck'
import { clockTime, count } from './format'
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
                {tab === 'new' && <NewMessageForm selection={selection} />}
                {tab === 'library' && <LibraryPanel selection={selection} />}
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
            className={`px-2 py-0.5  text-micro uppercase tracking-[0.14em] transition-colors ${active ? 'bg-signal-dim/30 text-signal' : 'text-ink-400 hover:text-ink-200'
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
    const [loading, setLoading] = useState(false)
    const [loadError, setLoadError] = useState<string | null>(null)
    const [askSave, setAskSave] = useState(false)
    const [askRetime, setAskRetime] = useState(false)

    const load = useCallback(async () => {
        if (!selection) {
            setDetail(null)
            setDraft(null)
            return
        }
        setLoading(true)
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
        } finally {
            setLoading(false)
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
    if (selection && !detail && loading) {
        return <LoadingLine />
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

    const saveToLibrary = async (name: string) => {
        setAskSave(false)
        if (!name.trim()) return
        setBusy(true)
        const saved = await run(() => api.saveToLibrary(detail.id, { name }), t('inspector.toLibrary'))
        setBusy(false)
        if (saved) notify('success', t('inspector.saved', { name: saved.name }))
    }

    const retime = async (next: string) => {
        setAskRetime(false)
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
                    <span
                        className={`badge ${detail.problem ? 'badge-blocked'
                            : isCapture ? 'badge-capture'
                                : detail.sent ? 'badge-sent'
                                    : detail.skipped ? 'badge-skipped' : 'badge-pending'}`}
                        title={detail.skipped && !detail.sent ? t('row.skippedTitle') : undefined}
                    >
                        {detail.problem ? t('row.blocked')
                            : isCapture ? t('inspector.fromDkm')
                                : detail.sent ? t('row.sent')
                                    : detail.skipped ? t('row.skipped') : t('row.pending')}
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
                <div className="border border-danger/50 bg-danger/10 text-danger px-2 py-1.5">
                    {detail.problem}
                </div>
            )}

            <PromptDialog
                open={askSave}
                title={t('inspector.toLibrary')}
                body={t('inspector.savePrompt')}
                label={t('library.nameLabel')}
                initial={`${detail.type ?? detail.msgId} #${detail.id}`}
                confirmLabel={t('inspector.toLibrary')}
                busy={busy}
                validate={(value) => (value.trim() ? null : t('library.nameRequired'))}
                onConfirm={(value) => void saveToLibrary(value)}
                onCancel={() => setAskSave(false)}
            />

            <PromptDialog
                open={askRetime}
                kind="number"
                title={t('inspector.retime')}
                body={t('inspector.retimePrompt')}
                hint={t('inspector.retimeHint')}
                label={t('inspector.retimeLabel')}
                unit="ms"
                initial={String(detail.timestamp)}
                confirmLabel={t('inspector.retime')}
                busy={busy}
                validate={(value) => {
                    const parsed = Number(value)
                    if (!Number.isFinite(parsed)) return t('inspector.retimeNaN')
                    if (parsed < 0) return t('inspector.retimeNegative')
                    return null
                }}
                onConfirm={(value) => void retime(value)}
                onCancel={() => setAskRetime(false)}
            />

            {readOnlyReason && !detail.problem && (
                <div className=" border border-ink-600 bg-ink-850 text-ink-400 px-2 py-1.5">
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
                <button className="btn btn-primary flex items-center gap-1.5"
                    disabled={readOnly || !dirty || busy} onClick={apply}>
                    {busy && <LoadingSpinner size={12} />}
                    <Icon name="check" size={12} />{t('inspector.apply')}
                </button>
                <button className="btn" disabled={!dirty || busy}
                    onClick={() => setDraft((detail.payload ?? {}) as Record<string, unknown>)}>
                    <Icon name="revert" size={12} />{t('inspector.revert')}
                </button>
                <div className="flex-1" />
                {!isCapture && (
                    <>
                        <button className="btn" disabled={readOnly || busy} onClick={() => setAskRetime(true)}
                            title={t('inspector.retimeTitle')}>
                            <Icon name="clock" size={11} />{t('inspector.retime')}
                        </button>
                        <button className="btn" disabled={!detail.decodable || busy} onClick={() => setAskSave(true)}
                            title={t('inspector.toLibraryTitle')}>
                            <Icon name="bookmark" size={11} />{t('inspector.toLibrary')}
                        </button>
                        <button className="btn btn-danger" disabled={detail.sent || running || busy} onClick={remove}>
                            <Icon name="trash" size={11} />{t('inspector.delete')}
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
function NewMessageForm({ selection }: { selection: Selection | null }) {
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
    /**
     * Where an insert lands. Two positions cover every real intent, and naming
     * both of them beats a bare number field: "position 4207" says nothing about
     * whether that is where the operator meant, whereas "after the selected
     * message" is checkable against what they can see in the list.
     */
    const [placement, setPlacement] = useState<'after' | 'end'>('after')
    const [verdict, setVerdict] = useState<Placement | null>(null)
    const checkPlacement = usePlacementCheck()

    const typeName = chosenType ?? stimulusTypes[0]?.qualifiedName ?? ''

    // The order field follows the placement unless the operator typed one, in
    // which case it is theirs and nothing overwrites it.
    const selectedIndex = selection?.mode === 'input' && selection.index !== undefined
        ? selection.index
        : null

    const canPlaceAfter = selectedIndex !== null
    const derivedIndex = placement === 'after' && canPlaceAfter ? selectedIndex + 1 : sessionCount
    const index = chosenIndex ?? derivedIndex

    useEffect(() => {
        if (!typeName) return
        void api.template(typeName).then((template) => setDraft(template.payload)).catch(() => setDraft({}))
    }, [typeName])

    const type = stimulusTypes.find((m) => m.qualifiedName === typeName) ?? null

    // Item 7's rule, at the one point where the operator can break it: the list
    // is always ascending, so an insert either lands where they asked or they
    // are told where it will land instead.
    const insert = async () => {
        if (!type) return
        const anchor = placement === 'after' && canPlaceAfter ? selectedIndex : null
        const check = await checkPlacement(anchor, offsetMillis)
        if (check.verdict !== 'ok') {
            setVerdict(check)
            return
        }
        void commit()
    }

    const commit = async () => {
        if (!type) return
        setBusy(true)
        const inserted = await run(
            () => api.insertMessage({ type: type.qualifiedName, index, offsetMillis, payload: draft }),
            t('new.insert'))
        setBusy(false)
        if (inserted) {
            touchSession()
            // Back to following the placement rule, so a run of inserts stays
            // predictable instead of piling up at whatever index was last typed.
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

            <div className="flex items-start gap-2">
                <span className="w-44 shrink-0 text-ink-300 pt-0.5">{t('new.placement')}</span>
                <span className="flex-1 min-w-0">
                    <Segmented<'after' | 'end'>
                        value={canPlaceAfter ? placement : 'end'}
                        onChange={(next) => { setPlacement(next); setChosenIndex(null) }}
                        options={[
                            {
                                value: 'after',
                                label: t('new.placeAfter'),
                                title: t('new.placeAfterTitle'),
                            },
                            { value: 'end', label: t('new.placeEnd'), title: t('new.placeEndTitle') },
                        ]}
                    />
                    {!canPlaceAfter && (
                        <span className="block text-ink-500 mt-1">{t('new.noSelection')}</span>
                    )}
                </span>
            </div>

            <label className="flex items-center gap-2">
                <span className="w-44 shrink-0 text-ink-300">{t('new.index')}</span>
                <NumberField className="field" integer min={0} max={sessionCount}
                    value={index} onChange={setChosenIndex} />
            </label>

            <label className="flex items-start gap-2">
                <span className="w-44 shrink-0 text-ink-300 pt-1">{t('new.offset')}</span>
                <span className="flex-1">
                    <NumberField className="field" integer min={0}
                        value={offsetMillis} onChange={setOffsetMillis} />
                    <span className="block text-ink-500 mt-1">{t('new.offsetHint')}</span>
                </span>
            </label>

            <AlertDialog
                open={verdict !== null}
                tone={verdict?.verdict === 'negative' ? 'danger' : 'caution'}
                title={verdict?.verdict === 'negative'
                    ? t('dialog.negativeTitle') : t('dialog.placeTitle')}
                body={verdict?.verdict === 'negative'
                    ? t('dialog.negativeBody', { offset: count(verdict.offsetMillis) })
                    : t('dialog.placeBody', {
                        offset: count(offsetMillis),
                        index: count((selectedIndex ?? 0) + 1),
                        timestamp: count(verdict?.verdict === 'reordered' ? verdict.timestamp : 0),
                        next: count(verdict?.verdict === 'reordered' ? verdict.nextTimestamp : 0),
                    })}
                detail={verdict?.verdict === 'reordered'
                    ? t('dialog.placeHint', { max: count(verdict.maxOffsetMillis) })
                    : null}
                confirmLabel={verdict?.verdict === 'negative'
                    ? t('dialog.negativeConfirm') : t('dialog.placeConfirm')}
                cancelLabel={verdict?.verdict === 'negative' ? undefined : t('dialog.startBack')}
                busy={busy}
                onConfirm={() => {
                    const kind = verdict?.verdict
                    setVerdict(null)
                    if (kind === 'reordered') void commit()
                }}
                onCancel={() => setVerdict(null)}
            />

            {schema && type && (
                <div className="border-t border-ink-700 pt-3">
                    <FieldEditor schema={schema} type={type} value={draft} readOnly={running}
                        onChange={(path, next) => setDraft((current) => setIn(current, path, next))} />
                </div>
            )}

            <div className="flex items-center gap-2 pt-1 border-t border-ink-700">
                <button className="btn btn-primary flex items-center gap-1.5"
                    disabled={!type || busy || running} onClick={insert}>
                    {busy && <LoadingSpinner size={12} />}
                    {t('new.insert')}
                </button>
                {running && <span className="text-caution">{t('new.pauseFirst')}</span>}
            </div>
        </div>
    )
}
