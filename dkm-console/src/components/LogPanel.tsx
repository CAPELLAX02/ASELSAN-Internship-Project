import { useEffect, useMemo, useRef, useState } from 'react'

import type { LinkTelemetry } from '../api/types'
import { useT, type Translate } from '../i18n/useT'
import { useStore } from '../store/useStore'
import { bytes, clockTime, count, rate } from './format'

const LEVEL_TONE: Record<string, string> = {
    INFO: 'text-ink-400',
    WARN: 'text-caution',
    ERROR: 'text-danger',
}

/** FR-32's narrative half: what happened, with timestamps — plus live rates. */
export function LogPanel() {
    const t = useT()
    const log = useStore((s) => s.log)
    const telemetry = useStore((s) => s.telemetry)
    const [follow, setFollow] = useState(true)
    const [level, setLevel] = useState<'ALL' | 'WARN'>('ALL')
    const scrollRef = useRef<HTMLDivElement | null>(null)

    const filtered = useMemo(
        () => (level === 'ALL' ? log : log.filter((line) => line.level !== 'INFO')),
        [log, level],
    )

    useEffect(() => {
        if (follow && scrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight
        }
    }, [filtered, follow])

    return (
        <div className="panel h-full min-h-0" data-tour="log">
            <div className="panel-title">
                <span>{t('log.title')}</span>
                <span className="flex items-center gap-1.5 normal-case tracking-normal">
                    <button className={`btn py-0 text-micro ${level === 'WARN' ? 'btn-primary' : ''}`}
                        onClick={() => setLevel(level === 'ALL' ? 'WARN' : 'ALL')}
                        title={t('log.warningsTitle')}>
                        {t('log.warnings')}
                    </button>
                    <button className={`btn py-0 text-micro ${follow ? 'btn-primary' : ''}`}
                        onClick={() => setFollow((value) => !value)}>{t('log.follow')}</button>
                </span>
            </div>

            {telemetry && telemetry.links.length > 0 && (
                <div className="flex gap-3 px-2 py-1 border-b border-ink-700 bg-ink-850/60 flex-wrap text-micro">
                    {telemetry.links.map((link) => <LinkMeter key={link.name} t={t} link={link} />)}
                    <span className="text-ink-500">
                        {t('log.vizFrames', { frames: count(telemetry.vizFramesSent) })}
                        {telemetry.vizFramesSkipped > 0 && (
                            <span className="text-caution">
                                {' · '}{t('log.vizSkipped', { count: count(telemetry.vizFramesSkipped) })}
                            </span>
                        )}
                        {telemetry.vizSamplesDropped > 0 && (
                            <span className="text-caution">
                                {' · '}{t('log.vizDropped', { count: count(telemetry.vizSamplesDropped) })}
                            </span>
                        )}
                        {telemetry.vizStimulusThinned > 0 && (
                            <span className="text-ink-400" title={t('log.vizThinnedTitle')}>
                                {' · '}{t('log.vizThinned', { count: count(telemetry.vizStimulusThinned) })}
                            </span>
                        )}
                    </span>
                </div>
            )}

            <div ref={scrollRef} className="flex-1 overflow-auto min-h-0 px-2 py-1">
                {filtered.map((line) => (
                    <div key={line.seq} className="flex gap-2 leading-5">
                        <span className="text-ink-600 shrink-0">{clockTime(line.t)}</span>
                        <span className="w-16 shrink-0 text-ink-500 truncate">{line.source}</span>
                        <span className={`${LEVEL_TONE[line.level] ?? 'text-ink-400'} min-w-0`}>{line.message}</span>
                    </div>
                ))}
                {filtered.length === 0 && <div className="text-ink-600">{t('log.empty')}</div>}
            </div>
        </div>
    )
}

function LinkMeter({ t, link }: { t: Translate; link: LinkTelemetry }) {
    const stalled = link.writeStalls > 0
    return (
        <span className="text-ink-500" title={t('log.stallsTitle', {
            sent: count(link.messagesOut),
            received: count(link.messagesIn),
            stalls: count(link.writeStalls),
        })}>
            <span className="text-ink-300">{link.name}</span>
            {' ↑'}{rate(link.bytesOutPerSecond)}
            {' ↓'}{rate(link.bytesInPerSecond)}
            {' · '}{bytes(link.bytesOut)}
            {stalled && (
                <span className="text-caution"> · {t('log.stalls', { count: count(link.writeStalls) })}</span>
            )}
        </span>
    )
}
