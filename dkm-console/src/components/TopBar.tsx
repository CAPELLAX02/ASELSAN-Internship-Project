import { useEffect, useRef, useState } from 'react'

import { api, downloadBinary, uploadInput } from '../api/client'
import type { LinkState, PlaybackStateName } from '../api/types'
import { LANGUAGES } from '../i18n'
import { useT } from '../i18n/useT'
import { useStore, type ThemeChoice } from '../store/useStore'
import { AlertDialog } from './AlertDialog'
import { Icon, type IconName } from './Icon'
import { bytes, count, duration, number, rate } from './format'
import type { Selection } from './MessagePanel'
import { LoadingSpinner } from './LoadingSpinner'

const LINK_STYLES: Record<LinkState, string> = {
    CONNECTED: 'border-good/60 text-good bg-good/10',
    LISTENING: 'border-caution/50 text-caution bg-caution/10',
    CLOSED: 'border-ink-500 text-ink-400 bg-ink-800',
    FAILED: 'border-danger/60 text-danger bg-danger/10',
    DOWN: 'border-ink-600 text-ink-400 bg-ink-800',
}

const SPEEDS = [0.25, 0.5, 1, 2, 5, 10, 25, 100]
const THEME_ORDER: ThemeChoice[] = ['system', 'light', 'dark']

const THEME_ICON: Record<string, IconName> = { dark: 'moon', light: 'sun', system: 'monitor' }

export function TopBar({ selection, onResetLayout, layoutMoved }: {
    selection: Selection | null
    onResetLayout: () => void
    /** Whether any seam has been moved, so the control can disable itself. */
    layoutMoved: boolean
}) {
    const t = useT()
    const links = useStore((s) => s.links)
    const playback = useStore((s) => s.playback)
    const telemetry = useStore((s) => s.telemetry)
    const sessionCount = useStore((s) => s.sessionCount)
    const sessionSource = useStore((s) => s.sessionSource)
    const captureTotal = useStore((s) => s.captureTotal)
    const eventsConnected = useStore((s) => s.eventsConnected)
    const vizConnected = useStore((s) => s.vizConnected)
    const run = useStore((s) => s.run)
    const notify = useStore((s) => s.notify)
    const touchSession = useStore((s) => s.touchSession)
    const pending = useStore((s) => s.pending)
    const setVizFrozen = useStore((s) => s.setVizFrozen)
    const setStepping = useStore((s) => s.setStepping)

    const [busy, setBusy] = useState(false)
    const [problemCount, setProblemCount] = useState(0)
    const [askProblems, setAskProblems] = useState(false)
    const [askStart, setAskStart] = useState(false)
    const [askStop, setAskStop] = useState(false)
    const [startType, setStartType] = useState<string | null>(null)

    const running = playback.state === 'RUNNING'
    const paused = playback.state === 'PAUSED'
    /** A run already under way: stepping continues it rather than repositioning. */
    const stepped = playback.sent > 0 && paused

    const act = async (action: () => Promise<unknown>, describe: string) => {
        setBusy(true)
        await run(action, describe)
        setBusy(false)
    }

    /**
     * Stopping holds the picture still, so the last frame of a run stays
     * readable. Starting or resuming releases it again -- a run that began
     * against a frozen canvas would look like nothing was happening.
     */
    const stop = () => act(async () => {
        const snapshot = await api.stop(true)
        // An explicit freeze supersedes the stepping hold: the run is over, so
        // there is nothing left to step, and the picture stops taking samples
        // rather than merely stopping its clock.
        setStepping(false)
        setVizFrozen(true)
        return snapshot
    }, t('transport.stop'))

    // Stopping rewinds, so a run that is part way through loses its place. Worth
    // one question -- but only when there is something to lose: asking before a
    // stop that discards nothing is the kind of prompt people learn to click
    // through without reading, which is how the one that matters gets missed.
    const onStop = () => {
        if (playback.sent > 0 && (running || paused)) {
            setAskStop(true)
            return
        }
        void stop()
    }

    /** Where a fresh run would begin: a selected stimulus message, or the top. */
    const startFrom = selection?.mode === 'input' ? selection : null

    const begin = async () => {
        setVizFrozen(false)
        setStepping(false)
        await act(() => (paused ? api.resume() : api.start(startFrom?.id ?? 0)),
            paused ? t('transport.resume') : t('transport.start'))
    }

    // A run that has reached its last message is over; making the operator press
    // Stop to say so leaves the transport claiming a run is in progress when
    // nothing is being sent. Rewinding here is what Stop does, so the set is
    // immediately runnable again.
    const onStartAfterProblems = async () => {
        if (startFrom) {
            const detail = await run(() => api.sessionMessage(startFrom.id))
            setStartType(detail?.type ?? null)
        } else {
            setStartType(null)
        }
        setAskStart(true)
    }

    const finishing = useRef(false)
    useEffect(() => {
        if (playback.state !== 'FINISHED' || finishing.current) {
            finishing.current = playback.state === 'FINISHED'
            return
        }
        finishing.current = true
        void run(async () => {
            const snapshot = await api.stop(true)
            setVizFrozen(true)
            return snapshot
        }, t('transport.stop'))
    }, [playback.state, run, setVizFrozen, t])

    // FR-31/NFR-5: messages that failed to decode are kept and saved byte for
    // byte, but the run skips them. Worth saying before the run rather than
    // after, when the gap in the output is all there is to go on.
    useEffect(() => {
        let cancelled = false
        void api.sessionMessages({ status: 'problem', limit: 1 })
            .then((page) => { if (!cancelled) setProblemCount(page.filtered) })
            .catch(() => { if (!cancelled) setProblemCount(0) })
        return () => { cancelled = true }
    }, [sessionCount, sessionSource])

    // Resuming needs no explanation -- the operator paused it and knows where it
    // is. A fresh start does: it either replays everything or begins part way
    // through, and those are different acts against a live link.
    const onStart = async () => {
        if (paused) {
            void begin()
            return
        }
        if (problemCount > 0) {
            setAskProblems(true)
            return
        }
        if (startFrom) {
            const detail = await run(() => api.sessionMessage(startFrom.id))
            setStartType(detail?.type ?? null)
        } else {
            setStartType(null)
        }
        setAskStart(true)
    }

    const onUpload = async (file: File | undefined) => {
        if (!file) return
        setBusy(true)
        const started = performance.now()
        const result = await run(() => uploadInput(file), t('file.load'))
        const elapsed = performance.now() - started
        setBusy(false)
        if (result) {
            touchSession()
            const problems = result.problems.length
            // How long it took and how fast it went, because "13,648 messages"
            // alone says nothing about whether this tool can carry the file the
            // operator is about to hand it.
            const rateText = elapsed > 0
                ? t('file.rate', {
                    rate: bytes((result.bytes / elapsed) * 1000),
                    ms: number(elapsed, elapsed < 100 ? 1 : 0),
                })
                : undefined
            notify(problems ? 'warning' : 'success',
                problems
                    ? t('file.loadedWithProblems', {
                        count: count(result.messages), name: file.name, problems,
                    })
                    : t('file.loaded', { count: count(result.messages), name: file.name }),
                rateText)
        }
    }

    const progress = playback.planned > 0
        ? Math.min(100, (playback.sent / playback.planned) * 100)
        : 0

    const totalIn = telemetry?.links.reduce((sum, l) => sum + l.bytesInPerSecond, 0) ?? 0
    const totalOut = telemetry?.links.reduce((sum, l) => sum + l.bytesOutPerSecond, 0) ?? 0
    const lag = playback.lagMillis ?? 0

    return (
        <header className="shrink-0 border-b border-ink-700 bg-ink-900">
            <div className="flex items-center gap-3 px-3 py-2 flex-wrap">
                <div className="flex items-center gap-2 pr-2 mr-1 border-r border-ink-700">
                    <span className="text-signal font-semibold tracking-wide">{t('app.brand')}</span>
                    <span className="text-ink-400 text-mini">{t('app.brandSub')}</span>
                </div>

                {/* FR-15: link state is the first thing on screen, because a run that
            starts with a link down produces silence rather than an error. */}
                <div className="flex items-center gap-1.5" data-tour="links">
                    {links.map((link) => (
                        <span
                            key={link.name}
                            className={`chip ${LINK_STYLES[link.state]}`}
                            title={`${link.host}:${link.port} — ${t(`link.${link.state}` as never)}\n${link.detail}`}
                        >
                            <span className="w-1.5 h-1.5 rounded-full bg-current" />
                            {link.name}
                        </span>
                    ))}
                    {links.length === 0 && <span className="text-ink-400">{t('link.none')}</span>}
                </div>

                <div className="flex-1" />

                <div className="flex items-center gap-3 text-mini text-ink-400">
                    <span title={t('transport.rateOut')}>
                        &uarr; <span className="text-ink-200">{rate(totalOut)}</span>
                    </span>
                    <span title={t('transport.rateIn')}>
                        &darr; <span className="text-ink-200">{rate(totalIn)}</span>
                    </span>
                    {pending > 0 && <LoadingSpinner className="text-signal" />}
                    <span className={eventsConnected ? 'text-good' : 'text-danger'} title={t('transport.eventsTitle')}>
                        {t('transport.events')}
                    </span>
                    <span className={vizConnected ? 'text-good' : 'text-danger'} title={t('transport.vizTitle')}>
                        {t('transport.viz')}
                    </span>
                </div>

                <Settings onResetLayout={onResetLayout} layoutMoved={layoutMoved} />
            </div>

            <div className="flex items-center gap-2 px-3 pb-2 flex-wrap">
                <div className="flex items-center gap-1" data-tour="transport">
                    <button
                        className="btn btn-primary"
                        disabled={busy || running}
                        onClick={onStart}
                        title={paused ? t('transport.resumeTitle') : t('transport.startTitle')}
                    >
                        <Icon name="play" size={13} />
                        {paused ? t('transport.resume') : t('transport.start')}
                    </button>
                    <button
                        className="btn"
                        disabled={busy || !running}
                        onClick={() => act(() => api.pause(), t('transport.pause'))}
                        title={t('transport.pauseTitle')}
                    >
                        <Icon name="pause" size={13} />
                        {t('transport.pause')}
                    </button>
                    {/* Mentor's case: a message that is small on the wire but costs
                        the DKM minutes of work. A paced run cannot wait for it --
                        the clock moves on -- so stepping sends exactly one and
                        leaves the operator to decide when the next goes. */}
                    <button
                        className="btn"
                        disabled={busy || running}
                        onClick={() => act(async () => {
                            setVizFrozen(false)
                            // The stepped message must reach the display, but
                            // nothing already on it may fade while the operator
                            // waits for the DKM to finish with it.
                            setStepping(true)
                            // Only on the first step: after that the run has a
                            // position of its own and the selection is just
                            // whatever the operator happens to be reading.
                            return api.step(1, stepped ? 0 : startFrom?.id ?? 0)
                        }, t('transport.step'))}
                        title={startFrom && !stepped
                            ? t('transport.stepFromTitle', { index: count((startFrom.index ?? 0) + 1) })
                            : t('transport.stepTitle')}
                    >
                        <Icon name="step" size={13} />
                        {t('transport.step')}
                    </button>
                    <button
                        className="btn btn-danger"
                        disabled={busy || playback.state === 'IDLE'}
                        onClick={onStop}
                        title={t('transport.stopTitle')}
                    >
                        <Icon name="stop" size={13} />
                        {t('transport.stop')}
                    </button>
                </div>

                <div className="flex items-center gap-1.5 pl-2 border-l border-ink-700">
                    <span className="text-ink-400 text-mini">{t('transport.speed')}</span>
                    <input
                        type="range"
                        className="w-28"
                        aria-label={t('transport.speed')}
                        min={0}
                        max={SPEEDS.length - 1}
                        step={1}
                        value={Math.max(0, SPEEDS.indexOf(playback.speed))}
                        onChange={(e) => act(() => api.setSpeed(SPEEDS[Number(e.target.value)]), t('transport.speed'))}
                        title={t('transport.speedTitle')}
                    />
                    <span className="w-12 text-signal">{playback.speed}&times;</span>
                    <button
                        className={`btn text-mini ${playback.mode === 'MAX_RATE' ? 'btn-primary' : ''}`}
                        disabled={busy}
                        onClick={() => act(
                            async () => {
                                const next = playback.mode === 'MAX_RATE' ? 'TIMESTAMP' : 'MAX_RATE'
                                const snapshot = await api.setMode(next)
                                // Full rate is a measurement tool, not a way to
                                // run faster: it throws the recording's timing
                                // away, and the cross-link ordering the DKM
                                // depends on survives only by luck at speed.
                                if (next === 'MAX_RATE') notify('warning', t('transport.maxRateWarning'))
                                return snapshot
                            },
                            t('transport.timed'))}
                        title={t('transport.modeTitle')}
                    >
                        {playback.mode === 'MAX_RATE' ? t('transport.maxRate') : t('transport.timed')}
                    </button>
                </div>

                <div className="flex items-center gap-2 pl-2 border-l border-ink-700 min-w-[260px]">
                    <div className="h-1.5 w-32  bg-ink-800 overflow-hidden">
                        <div
                            className="h-full bg-signal transition-[width] duration-150"
                            style={{ width: `${progress}%` }}
                        />
                    </div>
                    <span className="text-mini text-ink-400">
                        <span className="text-ink-100">{count(playback.sent)}</span>
                        {' / '}{count(playback.planned)}
                        {' · '}<span className="text-ink-200">{bytes(playback.sentBytes)}</span>
                        {playback.spanMillis > 0 && <> {' · '}t+{duration(playback.virtualMillis)}</>}
                    </span>
                    {/* The number that says whether this speed is actually being reproduced.
              Only shown while it means something. */}
                    {running && (
                        <span
                            className={`text-mini tabular-nums ${lag > 50 ? 'text-caution' : 'text-ink-500'}`}
                            title={t('transport.lagTitle')}
                        >
                            {t('transport.lag', { lag: Math.round(lag) })}
                        </span>
                    )}
                </div>

                <div className="flex-1" />

                <div className="flex items-center gap-1.5" data-tour="files">
                    <label className="btn cursor-pointer" title={t('file.loadTitle')}>
                        <Icon name="open" size={13} />{t('file.load')}
                        <input
                            type="file"
                            className="hidden"
                            accept=".bin,application/octet-stream"
                            onChange={(e) => { void onUpload(e.target.files?.[0]); e.target.value = '' }}
                        />
                    </label>
                    <button
                        className="btn"
                        disabled={sessionCount === 0}
                        onClick={() => downloadBinary('/api/session/export', 'input.bin')}
                        title={t('file.saveInputTitle')}
                    >
                        <Icon name="saveIn" size={13} />{t('file.saveInput')}
                    </button>
                    <button
                        className="btn"
                        disabled={captureTotal === 0}
                        onClick={() => downloadBinary('/api/capture/export', 'output.bin')}
                        title={t('file.saveOutputTitle')}
                    >
                        <Icon name="saveOut" size={13} />{t('file.saveOutput')}
                    </button>
                </div>

                <span className="text-mini text-ink-400 pl-2 border-l border-ink-700">
                    {t('file.summary', {
                        source: sessionSource,
                        input: count(sessionCount),
                        output: count(captureTotal),
                    })}
                </span>
            </div>

            <PlaybackStateBanner state={playback.state} error={playback.error} />

            <AlertDialog
                open={askProblems}
                tone="caution"
                title={t('dialog.problemsTitle')}
                body={t('dialog.problemsBody', { count: count(problemCount) })}
                confirmLabel={t('dialog.problemsConfirm')}
                cancelLabel={t('dialog.problemsReview')}
                busy={busy}
                onConfirm={() => { setAskProblems(false); void onStartAfterProblems() }}
                onCancel={() => setAskProblems(false)}
            />

            <AlertDialog
                open={askStop}
                title={t('dialog.stopTitle')}
                body={t('dialog.stopBody', {
                    sent: count(playback.sent), planned: count(playback.planned),
                })}
                detail={running ? t('dialog.stopHint') : null}
                confirmLabel={t('dialog.stopConfirm')}
                busy={busy}
                onConfirm={() => { setAskStop(false); void stop() }}
                onCancel={() => setAskStop(false)}
            />

            <AlertDialog
                open={askStart}
                tone="signal"
                title={startFrom ? t('dialog.startFromTitle') : t('dialog.startTitle')}
                body={startFrom
                    ? t('dialog.startFromBody', {
                        index: count((startFrom.index ?? 0) + 1),
                        type: startType ?? '',
                    })
                    : t('dialog.startBody')}
                detail={startFrom ? t('dialog.startFromHint') : t('dialog.startHint')}
                confirmLabel={startFrom ? t('dialog.startFromConfirm') : t('dialog.startConfirm')}
                cancelLabel={t('dialog.startBack')}
                busy={busy}
                onConfirm={() => { setAskStart(false); void begin() }}
                onCancel={() => setAskStart(false)}
            />
        </header>
    )
}

function Settings({ onResetLayout, layoutMoved }: {
    onResetLayout: () => void
    layoutMoved: boolean
}) {
    const t = useT()
    const lang = useStore((s) => s.lang)
    const setLang = useStore((s) => s.setLang)
    const theme = useStore((s) => s.theme)
    const setTheme = useStore((s) => s.setTheme)
    const openTour = useStore((s) => s.openTour)

    const nextTheme = THEME_ORDER[(THEME_ORDER.indexOf(theme) + 1) % THEME_ORDER.length]

    return (
        <div className="flex items-center gap-1.5 pl-3 ml-1 border-l border-ink-700">
            {/* The icon names the mode the button is in, not the one it goes to:
                it is a state readout that also happens to advance. */}
            <button
                className="btn text-mini py-0.5 w-20"
                onClick={() => setTheme(nextTheme)}
                title={`${t('settings.theme')}: ${t(`settings.theme.${theme}` as never)}`}
                aria-label={t('settings.theme')}
            >
                <Icon name={THEME_ICON[theme]} size={12} />
                {t(`settings.theme.${theme}` as never)}
            </button>

            <div
                className="flex  border border-ink-600 overflow-hidden"
                role="group"
                aria-label={t('settings.language')}
            >
                {LANGUAGES.map((entry) => (
                    <button
                        key={entry.code}
                        onClick={() => setLang(entry.code)}
                        aria-pressed={lang === entry.code}
                        className={`px-1.5 py-0.5 text-mini transition-colors ${lang === entry.code
                            ? 'bg-signal-dim/30 text-signal'
                            : 'bg-ink-800 text-ink-400 hover:text-ink-200'
                            }`}
                    >
                        {entry.label}
                    </button>
                ))}
            </div>

            {/* Beside the other things that are about the console rather than the
                run. Disabled when nothing has moved, so it reports the state of
                the layout as well as changing it. */}
            <button
                className="btn text-mini py-0.5 px-2"
                onClick={onResetLayout}
                disabled={!layoutMoved}
                title={t('layout.reset')}
                aria-label={t('layout.reset')}
            >
                <Icon name="layout" size={13} />
            </button>

            <button
                className="btn text-mini py-0.5 px-2"
                onClick={openTour}
                title={t('settings.help')}
                aria-label={t('settings.help')}
            >
                <Icon name="help" size={13} />
            </button>
        </div>
    )
}

function PlaybackStateBanner({ state, error }: { state: PlaybackStateName; error: string | null }) {
    const t = useT()
    if (error) {
        return (
            <div className="px-3 py-1 bg-danger/15 border-t border-danger/40 text-danger text-mini">
                {error}
            </div>
        )
    }
    if (state === 'PAUSED') {
        return (
            <div className="px-3 py-1 bg-caution/10 border-t border-caution/30 text-caution text-mini">
                {t('banner.paused')}
            </div>
        )
    }
    return null
}
