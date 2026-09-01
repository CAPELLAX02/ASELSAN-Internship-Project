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

export type ToastLevel = 'success' | 'info' | 'warning' | 'error'

export interface Toast {
    id: number
    level: ToastLevel
    message: string
    /** A figure worth setting apart -- a count, a duration, a rate. */
    detail?: string
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

    toasts: Toast[]

    /**
     * How many gateway actions are in flight. A count rather than a flag: two
     * overlapping requests must not have the first one to finish declare the
     * interface idle while the second is still running.
     */
    pending: number

    /**
     * Whether the plan view is holding its picture still.
     *
     * <p>Lives here rather than inside the view because two things outside it
     * freeze it: pressing Stop, and a run reaching its last message. Both are
     * moments the operator will want to read the final picture, and neither is
     * something the canvas can see for itself.
     */
    vizFrozen: boolean

    setLang: (lang: Lang) => void
    setTheme: (theme: ThemeChoice) => void
    openTour: () => void
    closeTour: (remember: boolean) => void

    bootstrap: () => Promise<void>
    setVizConnected: (connected: boolean) => void
    notify: (level: ToastLevel | 'INFO' | 'WARN' | 'ERROR', message: string, detail?: string) => void
    dismissToast: (id: number) => void
    refreshPlayback: () => Promise<void>
    refreshLinks: () => Promise<void>
    touchSession: () => void
    setVizFrozen: (frozen: boolean) => void
    /**
     * Stepping through the set by hand, so the picture must stop ageing.
     *
     * <p>Separate from vizFrozen because the two want different things: a freeze
     * drops incoming frames, while stepping has to keep taking them -- the
     * stepped message is the entire point -- and only stop the clock they age
     * by. Two steps can be minutes apart, and marks that fade after eight
     * seconds leave the operator looking at an empty display.
     */
    stepping: boolean
    setStepping: (stepping: boolean) => void
    run: <T>(action: () => Promise<T>, describe?: string) => Promise<T | undefined>
}

const MAX_LOG = 800

let toastId = 0

const storedLang = readStored(STORAGE.lang)
const storedTheme = readStored(STORAGE.theme)

const initialLang: Lang = storedLang === 'tr' || storedLang === 'en' ? storedLang : detectLanguage()
setNumberLocale(initialLang)
applyLang(initialLang)

/**
 * Puts the language on the document element.
 *
 * <p>Not decoration: `text-transform: uppercase` is language-sensitive, and
 * without this every uppercased Turkish label loses its dotted capital -- the
 * interface says BEKLIYOR where it means BEKLİYOR. One attribute fixes it
 * everywhere, which is why the strings themselves stay in normal case.
 */
function applyLang(lang: Lang) {
    document.documentElement.lang = lang
}

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

    toasts: [],
    pending: 0,
    vizFrozen: false,
    stepping: false,

    setLang(lang) {
        writeStored(STORAGE.lang, lang)
        // Number separators follow the interface language, not the browser's.
        setNumberLocale(lang)
        applyLang(lang)
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

    async refreshPlayback() {
        set({ playback: await api.playback() })
    },

    async refreshLinks() {
        set({ links: await api.links() })
    },

    touchSession() {
        set((state) => ({ sessionVersion: state.sessionVersion + 1 }))
    },

    notify(level, message, detail) {
        // The old three levels still arrive from the event stream, so they are
        // mapped rather than forbidden: one vocabulary at the surface, both
        // accepted underneath.
        const mapped: ToastLevel = level === 'INFO' ? 'info'
            : level === 'WARN' ? 'warning'
                : level === 'ERROR' ? 'error'
                    : level
        set((state) => ({
            toasts: [
                ...state.toasts.slice(-3),
                { id: ++toastId, level: mapped, message, detail, at: Date.now() },
            ],
        }))
    },

    dismissToast(id) {
        set((state) => ({ toasts: state.toasts.filter((toast) => toast.id !== id) }))
    },

    async run(action, describe) {
        set((state) => ({ pending: state.pending + 1 }))
        try {
            return await action()
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error)
            get().notify('ERROR', describe ? `${describe}: ${message}` : message)
            return undefined
        } finally {
            set((state) => ({ pending: Math.max(0, state.pending - 1) }))
        }
    },

    setVizFrozen(frozen) {
        set({ vizFrozen: frozen })
    },

    setStepping(stepping) {
        set({ stepping })
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
                // An error the gateway reported is the operator's problem too,
                // so it is raised as well as filed.
                const toasts = line.level === 'ERROR'
                    ? [...state.toasts.slice(-3), {
                        id: ++toastId,
                        level: 'error' as ToastLevel,
                        message: `${line.source}: ${line.message}`,
                        at: line.t,
                    }]
                    : state.toasts
                return { log, toasts }
            })
            break
        }
        default:
            break
    }
}
