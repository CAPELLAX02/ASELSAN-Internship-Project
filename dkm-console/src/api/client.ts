import type {
    LibraryItem, LinkStatus, LogLine, MessageDetail, MessagePage,
    PlaybackSnapshot, Schema, TracePage, VizCatalog,
} from './types'

export class ApiError extends Error {
    readonly status: number
    readonly issues: string[]

    constructor(status: number, message: string, issues: string[] = []) {
        super(message)
        this.status = status
        this.issues = issues
    }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await fetch(path, {
        ...init,
        headers: init?.body ? { 'Content-Type': 'application/json', ...init?.headers } : init?.headers,
    })
    const text = await response.text()
    const body = text ? JSON.parse(text) : null
    if (!response.ok) {
        throw new ApiError(response.status, body?.message ?? response.statusText, body?.issues ?? [])
    }
    return body as T
}

const json = (body: unknown) => JSON.stringify(body)

export const api = {
    schema: () => request<Schema>('/api/schema'),
    vizCatalog: () => request<VizCatalog>('/api/schema/visualization'),

    links: () => request<LinkStatus[]>('/api/status/links'),
    log: (limit = 400) => request<LogLine[]>(`/api/status/log?limit=${limit}`),

    playback: () => request<PlaybackSnapshot>('/api/playback'),
    start: () => request<PlaybackSnapshot>('/api/playback/start', { method: 'POST' }),
    pause: () => request<PlaybackSnapshot>('/api/playback/pause', { method: 'POST' }),
    resume: () => request<PlaybackSnapshot>('/api/playback/resume', { method: 'POST' }),
    stop: (rewind: boolean) =>
        request<PlaybackSnapshot>(`/api/playback/stop?rewind=${rewind}`, { method: 'POST' }),
    setSpeed: (speed: number) =>
        request<PlaybackSnapshot>('/api/playback/speed', { method: 'PUT', body: json({ speed }) }),
    setMode: (mode: 'TIMESTAMP' | 'MAX_RATE') =>
        request<PlaybackSnapshot>('/api/playback/mode', { method: 'PUT', body: json({ mode }) }),

    sessionMessages: (params: Record<string, string | number | undefined>) =>
        request<MessagePage>(`/api/session/messages?${query(params)}`),
    sessionMessage: (id: number) => request<MessageDetail>(`/api/session/messages/${id}`),
    editMessage: (id: number, payload: Record<string, unknown>) =>
        request<MessageDetail>(`/api/session/messages/${id}`, { method: 'PUT', body: json({ payload }) }),
    retimeMessage: (id: number, timestamp: number) =>
        request<MessageDetail>(`/api/session/messages/${id}/timestamp`, {
            method: 'PUT', body: json({ timestamp }),
        }),
    insertMessage: (body: { type: string; index: number; offsetMillis: number; payload?: unknown }) =>
        request<MessageDetail>('/api/session/messages', { method: 'POST', body: json(body) }),
    deleteMessage: (id: number) =>
        request<{ deleted: boolean; total: number }>(`/api/session/messages/${id}`, { method: 'DELETE' }),
    template: (type: string) =>
        request<{ header: Record<string, number>; payload: Record<string, unknown> }>(
            `/api/session/template?type=${encodeURIComponent(type)}`),
    loadPath: (path: string) =>
        request<{ messages: number; bytes: number; notes: string[]; problems: string[] }>(
            '/api/session/load-path', { method: 'POST', body: json({ path }) }),
    clearSession: () => request<{ total: number }>('/api/session/clear', { method: 'POST' }),
    saveToLibrary: (id: number, body: { name: string; description?: string; tags?: string[] }) =>
        request<LibraryItem>(`/api/session/messages/${id}/library`, { method: 'POST', body: json(body) }),

    /** FR-32: one chronological view of what was sent and what came back. */
    trace: (params: Record<string, string | number | undefined>) =>
        request<TracePage>(`/api/trace?${query(params)}`),

    captureMessages: (params: Record<string, string | number | boolean | undefined>) =>
        request<MessagePage>(`/api/capture/messages?${query(params)}`),
    captureMessage: (id: number) => request<MessageDetail>(`/api/capture/messages/${id}`),
    clearCapture: () => request<{ total: number }>('/api/capture/clear', { method: 'POST' }),

    library: (q = '', type = '') =>
        request<{
            available: boolean
            reason: string | null
            directory: string | null
            schemaHash: string
            items: LibraryItem[]
        }>(`/api/library?${query({ q, type })}`),
    deleteLibraryItem: (id: string) =>
        request<{ deleted: boolean }>(`/api/library/${id}`, { method: 'DELETE' }),
    insertFromLibrary: (id: string, body: { index: number; offsetMillis: number; force?: boolean }) =>
        request<MessageDetail>(`/api/library/${id}/insert`, { method: 'POST', body: json(body) }),
}

function query(params: Record<string, string | number | boolean | undefined>): string {
    const search = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
        if (value !== undefined && value !== '') {
            search.set(key, String(value))
        }
    }
    return search.toString()
}

/** Uploads an input binary through the browser (FR-6). */
export async function uploadInput(file: File) {
    const form = new FormData()
    form.append('file', file)
    const response = await fetch('/api/session/load', { method: 'POST', body: form })
    const text = await response.text()
    const body = text ? JSON.parse(text) : null
    if (!response.ok) {
        throw new ApiError(response.status, body?.message ?? response.statusText)
    }
    return body as { messages: number; bytes: number; notes: string[]; problems: string[] }
}

/** Triggers a download of the current set as a binary (FR-10 / FR-21). */
export function downloadBinary(path: string, filename: string) {
    const anchor = document.createElement('a')
    anchor.href = path
    anchor.download = filename
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
}
