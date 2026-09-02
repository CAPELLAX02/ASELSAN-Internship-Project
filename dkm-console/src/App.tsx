import { useEffect, useRef, useState } from 'react'

import { InspectorPanel } from './components/InspectorPanel'
import { LogPanel } from './components/LogPanel'
import { MessagePanel, type Selection } from './components/MessagePanel'
import { RadarView } from './components/RadarView'
import { LoadingSpinner } from './components/LoadingSpinner'
import { Splitter, useSplit } from './components/Splitter'
import { ToastStack } from './components/Toast'
import { TooltipLayer } from './components/TooltipLayer'
import { TopBar } from './components/TopBar'
import { Tour } from './components/Tour'
import { useT } from './i18n/useT'
import { api } from './api/client'
import { applyTheme, useStore } from './store/useStore'

export default function App() {
    const t = useT()
    const ready = useStore((s) => s.ready)
    const fatal = useStore((s) => s.fatal)
    const bootstrap = useStore((s) => s.bootstrap)
    const schema = useStore((s) => s.schema)
    const theme = useStore((s) => s.theme)

    const [selection, setSelection] = useState<Selection | null>(null)

    // Three seams, three stored fractions. The middle column is what is left
    // over, so it needs no fraction of its own and cannot be squeezed to nothing
    // by the other two.
    const left = useSplit('left', 0.28)
    const right = useSplit('right', 0.24)
    const log = useSplit('log', 0.28)
    const main = useRef<HTMLDivElement | null>(null)
    const middle = useRef<HTMLDivElement | null>(null)

    const sessionVersion = useStore((s) => s.sessionVersion)
    const knownSession = useRef(sessionVersion)

    /**
     * Points the selection at the top of a set that has just been replaced.
     *
     * <p>Message ids are not reused across loads, so a selection held over from
     * the previous file names something that no longer exists -- and the next
     * thing that asks about it, which is anything the operator does with the
     * transport, fails with "no message with id". Selecting the first message is
     * also what the operator wants: a fresh set is read from the top, and Start
     * means "from the beginning" without them having to say so.
     */
    useEffect(() => {
        if (sessionVersion === knownSession.current) return
        knownSession.current = sessionVersion
        let cancelled = false
        void (async () => {
            try {
                const page = await api.sessionMessages({ offset: 0, limit: 1 })
                const first = page.items[0]
                if (cancelled) return
                setSelection(first ? { mode: 'input', id: first.id, index: 0 } : null)
            } catch {
                if (!cancelled) setSelection(null)
            }
        })()
        return () => { cancelled = true }
    }, [sessionVersion])

    // The stored preference has to reach the document before the first paint of
    // anything that reads it; the plan view samples these variables directly.
    useEffect(() => { applyTheme(theme) }, [theme])

    useEffect(() => { void bootstrap() }, [bootstrap])

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
            <div className="h-full flex items-center justify-center gap-2 text-ink-500">
                <LoadingSpinner size={16} />
                {t('app.loading')}
            </div>
        )
    }

    return (
        <div className="h-full flex flex-col">
            <TopBar
                selection={selection}
                onResetLayout={() => { left.reset(); right.reset(); log.reset() }}
                layoutMoved={left.moved || right.moved || log.moved}
            />

            {/* The stimulus list gets more room than the inspector: it carries six
                columns and is the panel the operator scans, while the inspector
                shows one message at a time in a stack of labelled fields. */}
            <main ref={main} className="flex-1 min-h-0 flex bg-ink-700">
                <div className="flex flex-col min-h-0 min-w-0"
                    style={{ width: `${left.fraction * 100}%` }}>
                    <MessagePanel selection={selection} onSelect={setSelection} />
                </div>

                <Splitter
                    orientation="vertical"
                    label={t('layout.leftSeam')}
                    onReset={left.reset}
                    onDrag={(clientX) => {
                        const box = main.current?.getBoundingClientRect()
                        if (box) left.commit((clientX - box.left) / box.width)
                    }}
                />

                <div ref={middle} className="flex-1 min-w-0 flex flex-col min-h-0">
                    {/* A direct flex child, not wrapped: the plan view sizes itself
                        with flex-1, which a plain block parent would have ignored --
                        leaving the panel as tall as its own content and the rest of
                        the column empty beneath it. */}
                    <RadarView />

                    <Splitter
                        orientation="horizontal"
                        label={t('layout.logSeam')}
                        onReset={log.reset}
                        onDrag={(_x, clientY) => {
                            const box = middle.current?.getBoundingClientRect()
                            if (box) log.commit((box.bottom - clientY) / box.height)
                        }}
                    />

                    {/* A floor under each, so a seam dragged to its stop leaves
                        both panels usable. Without it the plan view's canvas --
                        flex-1 over min-h-0 -- is free to shrink to nothing, and
                        its overlays end up floating over the log. */}
                    <div className="min-h-[7rem] shrink-0"
                        style={{ height: `${log.fraction * 100}%` }}>
                        <LogPanel />
                    </div>
                </div>

                <Splitter
                    orientation="vertical"
                    label={t('layout.rightSeam')}
                    onReset={right.reset}
                    onDrag={(clientX) => {
                        const box = main.current?.getBoundingClientRect()
                        if (box) right.commit((box.right - clientX) / box.width)
                    }}
                />

                <div className="flex flex-col min-h-0 min-w-0"
                    style={{ width: `${right.fraction * 100}%` }}>
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
                <span className="text-ink-600">{t('app.footerNote')}</span>
                <span className="px-3 border-l border-ink-700 text-ink-400">
                    {t('app.credit')}
                </span>
            </footer>

            <Tour />
            <ToastStack />
            <TooltipLayer />
        </div>
    )
}
