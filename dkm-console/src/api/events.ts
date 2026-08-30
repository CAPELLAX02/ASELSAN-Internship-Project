/**
 * The two WebSocket channels, kept separate for the same reason the server
 * keeps them separate: one carries JSON at human rates, the other carries
 * binary at frame rates, and neither should be able to delay the other.
 *
 * <p>Both reconnect on their own with a backoff. A reconnect is not a silent
 * event -- the caller is told, because anything that arrived while the socket
 * was down has to be re-read over REST rather than assumed.
 */

type Listener<T> = (value: T) => void

export interface GatewayEvent {
    type: string
    t: number
    data: Record<string, unknown>
}

class ReconnectingSocket {
    private socket: WebSocket | null = null
    private attempt = 0
    private closed = false
    private timer: number | undefined

    private readonly path: string
    private readonly binary: boolean
    private readonly onMessage: (data: unknown) => void
    private readonly onStateChange: (connected: boolean) => void

    constructor(
        path: string,
        binary: boolean,
        onMessage: (data: unknown) => void,
        onStateChange: (connected: boolean) => void,
    ) {
        this.path = path
        this.binary = binary
        this.onMessage = onMessage
        this.onStateChange = onStateChange
        this.open()
    }

    private open() {
        if (this.closed) return
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
        const socket = new WebSocket(`${protocol}//${location.host}${this.path}`)
        if (this.binary) {
            socket.binaryType = 'arraybuffer'
        }
        this.socket = socket

        socket.onopen = () => {
            this.attempt = 0
            this.onStateChange(true)
        }
        socket.onmessage = (event) => this.onMessage(event.data)
        socket.onclose = () => {
            this.onStateChange(false)
            this.scheduleReopen()
        }
        socket.onerror = () => socket.close()
    }

    private scheduleReopen() {
        if (this.closed) return
        // Backoff, capped: a gateway that is down for a while should not be hit
        // every 100 ms, but a gateway that just restarted should be picked up fast.
        const delay = Math.min(500 * 2 ** this.attempt++, 5000)
        this.timer = window.setTimeout(() => this.open(), delay)
    }

    close() {
        this.closed = true
        window.clearTimeout(this.timer)
        this.socket?.close()
    }
}

export function connectEvents(
    onEvent: Listener<GatewayEvent>,
    onStateChange: Listener<boolean>,
): () => void {
    const socket = new ReconnectingSocket('/ws/events', false, (data) => {
        if (typeof data !== 'string') return
        try {
            onEvent(JSON.parse(data) as GatewayEvent)
        } catch {
            // A malformed frame is not worth tearing the stream down for.
        }
    }, onStateChange)
    return () => socket.close()
}

export function connectViz(
    onFrame: Listener<ArrayBuffer>,
    onStateChange: Listener<boolean>,
): () => void {
    const socket = new ReconnectingSocket('/ws/viz', true, (data) => {
        if (data instanceof ArrayBuffer) {
            onFrame(data)
        }
    }, onStateChange)
    return () => socket.close()
}
