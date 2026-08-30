import { useEffect, useState } from 'react'

import { InspectorPanel } from './components/InspectorPanel'
import { LogPanel } from './components/LogPanel'
import { MessagePanel, type Selection } from './components/MessagePanel'
import { RadarView } from './components/RadarView'
import { TopBar } from './components/TopBar'
import { Tour } from './components/Tour'
import { useT } from './i18n/useT'
import { applyTheme, useStore } from './store/useStore'

export default function App() {
    const t = useT()
    const ready = useStore((s) => s.ready)
    const fatal = useStore((s) => s.fatal)
    const bootstrap = useStore((s) => s.bootstrap)
    const notice = useStore((s) => s.notice)
    const dismissNotice = useStore((s) => s.dismissNotice)
    const schema = useStore((s) => s.schema)
    const theme = useStore((s) => s.theme)

    const [selection, setSelection] = useState<Selection | null>(null)

    // The stored preference has to reach the document before the first paint of
    // anything that reads it; the plan view samples these variables directly.
    useEffect(() => { applyTheme(theme) }, [theme])

    useEffect(() => { void bootstrap() }, [bootstrap])

    useEffect(() => {
        if (!notice || notice.level === 'ERROR') return
        const timer = window.setTimeout(dismissNotice, 6000)
        return () => window.clearTimeout(timer)
    }, [notice, dismissNotice])

    if (fatal) {
        return (
            <div className="h-full flex items-center justify-center p-8">
                <div className="max-w-lg text-center">
                    <div className="text-danger text-[14px] mb-2">{t('app.unreachableTitle')}</div>
                    <div className="text-ink-400">{fatal}</div>
                    <div className="text-ink-500 mt-3">{t('app.unreachableHint')}</div>
                    <button className="btn mt-4" onClick={() => void bootstrap()}>{t('app.retry')}</button>
                </div>
            </div>
        )
    }

    if (!ready) {
        return (
            <div className="h-full flex items-center justify-center text-ink-500">
                {t('app.loading')}
            </div>
        )
    }

    return (
        <div className="h-full flex flex-col">
            <TopBar />

            <main
                className="flex-1 min-h-0 grid gap-2 p-2"
                style={{ gridTemplateColumns: 'minmax(360px, 1.05fr) minmax(420px, 1.5fr) minmax(340px, 1fr)' }}
            >
                <div className="flex flex-col gap-2 min-h-0">
                    <MessagePanel selection={selection} onSelect={setSelection} />
                </div>

                <div className="flex flex-col gap-2 min-h-0">
                    <RadarView />
                    <div className="h-48 shrink-0">
                        <LogPanel />
                    </div>
                </div>

                <div className="flex flex-col gap-2 min-h-0">
                    <InspectorPanel selection={selection} onSelect={setSelection} />
                </div>
            </main>

            <footer className="shrink-0 flex items-center gap-3 px-3 py-1 border-t border-ink-700
                         bg-ink-900 text-[10px] text-ink-500">
                <span>
                    {t('app.interface')} <span className="text-ink-300">{schema?.version}</span>
                    <span className="text-ink-600"> ({schema?.hash})</span>
                </span>
                <span>
                    {t('app.footerTypes', {
                        count: schema?.messages.length ?? 0,
                        bytes: schema?.sizeTBytes ?? 0,
                        order: schema?.byteOrder?.toLowerCase().replace('_', '-') ?? '',
                    })}
                </span>
                <div className="flex-1" />
                <span>{t('app.footerNote')}</span>
            </footer>

            <Tour />

            {notice && (
                <div className={`fixed bottom-4 left-1/2 -translate-x-1/2 max-w-2xl px-3 py-2 rounded border
                         shadow-lg backdrop-blur z-40 ${notice.level === 'ERROR'
                        ? 'bg-danger/20 border-danger/60 text-danger'
                        : notice.level === 'WARN'
                            ? 'bg-caution/20 border-caution/60 text-caution'
                            : 'bg-ink-800/95 border-ink-600 text-ink-200'
                    }`}>
                    <div className="flex items-start gap-3">
                        <span className="min-w-0">{notice.message}</span>
                        <button
                            className="text-current opacity-60 hover:opacity-100 shrink-0"
                            onClick={dismissNotice}
                            aria-label="close"
                        >
                            &times;
                        </button>
                    </div>
                </div>
            )}
        </div>
    )
}
