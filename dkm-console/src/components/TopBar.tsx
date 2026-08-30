import { useState } from 'react'

import { api, downloadBinary, uploadInput } from '../api/client'
import type { LinkState, PlaybackStateName } from '../api/types'
import { LANGUAGES } from '../i18n'
import { useT } from '../i18n/useT'
import { useStore, type ThemeChoice } from '../store/useStore'
import { bytes, count, duration, rate } from './format'

const LINK_STYLES: Record<LinkState, string> = {
    CONNECTED: 'border-good/60 text-good bg-good/10',
    LISTENING: 'border-caution/50 text-caution bg-caution/10',
    CLOSED: 'border-ink-500 text-ink-400 bg-ink-800',
    FAILED: 'border-danger/60 text-danger bg-danger/10',
    DOWN: 'border-ink-600 text-ink-400 bg-ink-800',
}

const SPEEDS = [0.25, 0.5, 1, 2, 5, 10, 25, 100]
const THEME_ORDER: ThemeChoice[] = ['system', 'light', 'dark']

export function TopBar() {
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

    const [busy, setBusy] = useState(false)

    const running = playback.state === 'RUNNING'
    const paused = playback.state === 'PAUSED'

    const act = async (action: () => Promise<unknown>, describe: string) => {
        setBusy(true)
        await run(action, describe)
        setBusy(false)
    }

    const onUpload = async (file: File | undefined) => {
        if (!file) return
        setBusy(true)
        const result = await run(() => uploadInput(file), t('file.load'))
        setBusy(false)
        if (result) {
            touchSession()
            const problems = result.problems.length
            notify(problems ? 'WARN' : 'INFO', problems
                ? t('file.loadedWithProblems', { count: count(result.messages), name: file.name, problems })
                : t('file.loaded', { count: count(result.messages), name: file.name }))
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
                    <span className={eventsConnected ? 'text-good' : 'text-danger'} title={t('transport.eventsTitle')}>
                        {t('transport.events')}
                    </span>
                    <span className={vizConnected ? 'text-good' : 'text-danger'} title={t('transport.vizTitle')}>
                        {t('transport.viz')}
                    </span>
                </div>

                <Settings />
            </div>

            <div className="flex items-center gap-2 px-3 pb-2 flex-wrap">
                <div className="flex items-center gap-1" data-tour="transport">
                    <button
                        className="btn btn-primary"
                        disabled={busy || running}
                        onClick={() => act(() => (paused ? api.resume() : api.start()),
                            paused ? t('transport.resume') : t('transport.start'))}
                        title={paused ? t('transport.resumeTitle') : t('transport.startTitle')}
                    >
                        {paused ? t('transport.resume') : t('transport.start')}
                    </button>
                    <button
                        className="btn"
                        disabled={busy || !running}
                        onClick={() => act(() => api.pause(), t('transport.pause'))}
                        title={t('transport.pauseTitle')}
                    >
                        {t('transport.pause')}
                    </button>
                    <button
                        className="btn btn-danger"
                        disabled={busy || playback.state === 'IDLE'}
                        onClick={() => act(() => api.stop(true), t('transport.stop'))}
                        title={t('transport.stopTitle')}
                    >
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
                            () => api.setMode(playback.mode === 'MAX_RATE' ? 'TIMESTAMP' : 'MAX_RATE'),
                            t('transport.timed'))}
                        title={t('transport.modeTitle')}
                    >
                        {playback.mode === 'MAX_RATE' ? t('transport.maxRate') : t('transport.timed')}
                    </button>
                </div>

                <div className="flex items-center gap-2 pl-2 border-l border-ink-700 min-w-[260px]">
                    <div className="h-1.5 w-32 rounded-full bg-ink-800 overflow-hidden">
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
                        {t('file.load')}
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
                        {t('file.saveInput')}
                    </button>
                    <button
                        className="btn"
                        disabled={captureTotal === 0}
                        onClick={() => downloadBinary('/api/capture/export', 'output.bin')}
                        title={t('file.saveOutputTitle')}
                    >
                        {t('file.saveOutput')}
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
        </header>
    )
}

function Settings() {
    const t = useT()
    const lang = useStore((s) => s.lang)
    const setLang = useStore((s) => s.setLang)
    const theme = useStore((s) => s.theme)
    const setTheme = useStore((s) => s.setTheme)
    const openTour = useStore((s) => s.openTour)

    const nextTheme = THEME_ORDER[(THEME_ORDER.indexOf(theme) + 1) % THEME_ORDER.length]

    return (
        <div className="flex items-center gap-1.5 pl-3 ml-1 border-l border-ink-700">
            <button
                className="btn text-mini py-0.5 w-16"
                onClick={() => setTheme(nextTheme)}
                title={`${t('settings.theme')}: ${t(`settings.theme.${theme}` as never)}`}
                aria-label={t('settings.theme')}
            >
                {t(`settings.theme.${theme}` as never)}
            </button>

            <div
                className="flex rounded border border-ink-600 overflow-hidden"
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

            <button
                className="btn text-mini py-0.5 px-2"
                onClick={openTour}
                title={t('settings.help')}
                aria-label={t('settings.help')}
            >
                ?
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
