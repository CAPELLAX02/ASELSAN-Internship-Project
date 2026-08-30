import { create } from 'zustand'

import { api } from '../api/client'
import { connectEvents, type GatewayEvent } from '../api/events'
import { setNumberLocale } from '../components/format'
import { detectLanguage, type Lang } from '../i18n'
import type {
    LinkStatus, LogLine, PlaybackSnapshot, Schema, Telemetry, VizCatalog,
} from '../api/types'

export type ThemeChoice = 'system' | 'light' | 'dark'

const STORAGE = {
    lang: 'dkm.lang',
    theme: 'dkm.theme',
    tour: 'dkm.tourSeen',
} as const

/**
 * localStorage is a convenience, not a dependency. A private window, cleared
 * site data or a locked-down browser makes it throw, and none of those should
 * stop an operator from running a stimulus set.
 */
function readStored(key: string): string | null {
    try {
        return localStorage.getItem(key)
    } catch {
        return null
    }
}

function writeStored(key: string, value: string) {
    try {
        localStorage.setItem(key, value)
    } catch {
        // Preference not remembered; the session still works.
    }
}

/**
 * The theme is applied to the document element rather than held in React: the
 * plan view reads its colours from CSS variables outside the React tree, and a
 * class on a wrapper would not reach it.
 */
export function applyTheme(choice: ThemeChoice) {
    const root = document.documentElement
    if (choice === 'system') {
        delete root.dataset.theme
    } else {
        root.dataset.theme = choice
    }
}

/**
 * Everything the interface needs that changes at a rate a person can read.
 *
 * <p>Deliberately does not hold the visualization stream. Sample-rate data goes
 * straight from the WebSocket into typed arrays in {@link Scene} and never
 * enters React state -- putting it here would mean a re-render per frame at
 * best, and a re-render per sample at worst.
 */

const IDLE_PLAYBACK: PlaybackSnapshot = {
    state: 'IDLE', speed: 1, mode: 'TIMESTAMP', sent: 0, sentBytes: 0, error: null,
    startedAt: 0, finishedAt: 0, planned: 0, plannedBytes: 0, spanMillis: 0,
    virtualMillis: 0, tracks: [],
}

export interface Notice {
    level: 'INFO' | 'WARN' | 'ERROR'
    message: string
    at: number
}

interface State {
    lang: Lang
    theme: ThemeChoice
    tourSeen: boolean
    tourOpen: boolean

    ready: boolean
    fatal: string | null
    eventsConnected: boolean
    vizConnected: boolean

    schema: Schema | null
    vizCatalog: VizCatalog | null

    links: LinkStatus[]
    playback: PlaybackSnapshot
    telemetry: Telemetry | null
    log: LogLine[]

    /** Bumped whenever the stimulus set changes, so lists know to refetch. */
    sessionVersion: number
    sessionSource: string
    sessionCount: number

    captureVersion: number
    captureTotal: number
    captureOverflowed: number

    notice: Notice | null

    setLang: (lang: Lang) => void
    setTheme: (theme: ThemeChoice) => void
    openTour: () => void
    closeTour: (remember: boolean) => void

    bootstrap: () => Promise<void>
    setVizConnected: (connected: boolean) => void
    notify: (level: Notice['level'], message: string) => void
    dismissNotice: () => void
    refreshPlayback: () => Promise<void>
    refreshLinks: () => Promise<void>
    touchSession: () => void
    run: <T>(action: () => Promise<T>, describe?: string) => Promise<T | undefined>
}

const MAX_LOG = 800

const storedLang = readStored(STORAGE.lang)
const storedTheme = readStored(STORAGE.theme)

const initialLang: Lang = storedLang === 'tr' || storedLang === 'en' ? storedLang : detectLanguage()
setNumberLocale(initialLang)

export const useStore = create<State>((set, get) => ({
    lang: initialLang,
    theme: storedTheme === 'light' || storedTheme === 'dark' ? storedTheme : 'system',
    tourSeen: readStored(STORAGE.tour) === '1',
    tourOpen: false,

    ready: false,
    fatal: null,
    eventsConnected: false,
    vizConnected: false,

    schema: null,
    vizCatalog: null,

    links: [],
    playback: IDLE_PLAYBACK,
    telemetry: null,
    log: [],

    sessionVersion: 0,
    sessionSource: '(empty)',
    sessionCount: 0,

    captureVersion: 0,
    captureTotal: 0,
    captureOverflowed: 0,

    notice: null,

    setLang(lang) {
        writeStored(STORAGE.lang, lang)
        // Number separators follow the interface language, not the browser's.
        setNumberLocale(lang)
        set({ lang })
    },

    setTheme(theme) {
        writeStored(STORAGE.theme, theme)
        applyTheme(theme)
        set({ theme })
    },

    openTour() {
        set({ tourOpen: true })
    },

    closeTour(remember) {
        if (remember) {
            writeStored(STORAGE.tour, '1')
            set({ tourSeen: true })
        }
        set({ tourOpen: false })
    },

    async bootstrap() {
        try {
            const [schema, vizCatalog, links, playback, log, session, capture] = await Promise.all([
                api.schema(), api.vizCatalog(), api.links(), api.playback(), api.log(),
                api.sessionMessages({ limit: 1 }), api.captureMessages({ limit: 1 }),
            ])
            set({
                schema, vizCatalog, links, playback, log, ready: true, fatal: null,
                sessionSource: session.source ?? '(empty)',
                sessionCount: session.total,
                captureTotal: capture.total,
                captureOverflowed: capture.overflowed ?? 0,
            })
            // The DKM is very often already attached by the time the console is
            // opened -- it is started first, by design. Waiting for a link *event*
            // would mean the tour never ran in the normal case.
            maybeOpenTour(get, set)
        } catch (error) {
            set({ fatal: `Could not reach the gateway: ${(error as Error).message}` })
            return
        }

        connectEvents(
            (event) => applyEvent(event, set, get),
            (connected) => {
                set({ eventsConnected: connected })
                if (connected) {
                    // Anything that happened while the socket was down was missed, so the
                    // authoritative state is re-read rather than assumed.
                    void get().refreshLinks()
                    void get().refreshPlayback()
                    set((state) => ({ sessionVersion: state.sessionVersion + 1 }))
                }
            },
        )
    },

    setVizConnected(connected) {
        set({ vizConnected: connected })
    },

    notify(level, message) {
        set({ notice: { level, message, at: Date.now() } })
    },

    dismissNotice() {
        set({ notice: null })
    },

    async refreshPlayback() {
        set({ playback: await api.playback() })
    },

    async refreshLinks() {
        set({ links: await api.links() })
    },

    touchSession() {
        set((state) => ({ sessionVersion: state.sessionVersion + 1 }))
    },

    async run(action, describe) {
        try {
            return await action()
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error)
            get().notify('ERROR', describe ? `${describe}: ${message}` : message)
            return undefined
        }
    },
}))

type Setter = (partial: Partial<State> | ((state: State) => Partial<State>)) => void

/** Opens the walkthrough the first time this browser sees an attached DKM. */
function maybeOpenTour(get: () => State, set: Setter) {
    const state = get()
    if (!state.tourSeen && !state.tourOpen && state.links.some((l) => l.state === 'CONNECTED')) {
        set({ tourOpen: true })
    }
}

function applyEvent(event: GatewayEvent, set: Setter, get: () => State) {
    const data = event.data ?? {}
    switch (event.type) {
        case 'link':
            void get().refreshLinks().then(() => maybeOpenTour(get, set))
            break
        case 'playback':
            set({ playback: data as unknown as PlaybackSnapshot })
            break
        case 'playbackProgress':
            set((state) => ({
                playback: {
                    ...state.playback,
                    sent: Number(data.sent ?? state.playback.sent),
                    sentBytes: Number(data.sentBytes ?? state.playback.sentBytes),
                    virtualMillis: Number(data.virtualMillis ?? state.playback.virtualMillis),
                    tracks: (data.tracks as PlaybackSnapshot['tracks']) ?? state.playback.tracks,
                },
            }))
            break
        case 'telemetry':
            set({ telemetry: data as unknown as Telemetry })
            break
        case 'capture':
            set((state) => ({
                captureVersion: state.captureVersion + 1,
                captureTotal: Number(data.total ?? state.captureTotal),
                captureOverflowed: Number(data.overflowed ?? state.captureOverflowed),
            }))
            break
        case 'session':
            set((state) => ({
                sessionVersion: state.sessionVersion + 1,
                sessionCount: Number(data.count ?? state.sessionCount),
                sessionSource: typeof data.source === 'string' ? data.source : state.sessionSource,
            }))
            break
        case 'log': {
            const line = data as unknown as LogLine
            set((state) => {
                // The backlog fetched at bootstrap and the live stream overlap around
                // the moment of subscribing, so a line either side of that instant
                // arrives twice. seq is the gateway's own counter, so anything not
                // newer than the newest line already held is that duplicate. A seq
                // older than the whole buffer is a restarted gateway numbering from
                // scratch, which is a new log rather than a repeat of this one.
                const newest = state.log[state.log.length - 1]
                if (newest && line.seq <= newest.seq && line.seq > newest.seq - MAX_LOG) return state
                const log = state.log.length >= MAX_LOG ? state.log.slice(-MAX_LOG + 1) : state.log.slice()
                log.push(line)
                return {
                    log,
                    notice: line.level === 'ERROR'
                        ? { level: 'ERROR', message: `${line.source}: ${line.message}`, at: line.t }
                        : state.notice,
                }
            })
            break
        }
        default:
            break
    }
}
