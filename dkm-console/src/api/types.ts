/**
 * Mirrors what the gateway serves from /api/schema. Nothing in this file
 * describes any particular message type -- the console renders whatever the
 * schema says exists, which is what keeps "add a message type" a schema change
 * rather than a front-end change (G1/NFR-1).
 */

export type FieldKind = 'PRIMITIVE' | 'STRUCT' | 'PRIMITIVE_ARRAY' | 'STRUCT_ARRAY'
export type Direction = 'TO_DKM' | 'FROM_DKM' | 'BIDIRECTIONAL'

export interface FieldDef {
    name: string
    kind: FieldKind
    type: string
    offset: number
    size: number
    elementSize: number
    array: boolean
    arrayLength: number
    countField: string | null
    stringLike: boolean
    unit: string | null
    doc: string | null
    correlationId: boolean
    enumValues?: Record<string, string>
    struct?: StructDef
    bits?: number
    signed?: boolean
}

export interface StructDef {
    name: string
    size: number
    alignment: number
    fields: FieldDef[]
}

export interface MessageDef {
    qualifiedName: string
    name: string
    module: string
    moduleId: number
    msgId: number
    direction: Direction
    size: number
    doc: string
    correlationField: string | null
    fields: FieldDef[]
}

export interface ModuleDef {
    name: string
    id: number
    dkm: boolean
    port: number
    description: string
    /** Index used by visualization frames to name a link; -1 for the DKM itself. */
    linkIndex: number
}

export interface Schema {
    version: string
    hash: string
    sizeTBytes: number
    byteOrder: string
    headerSize: number
    modules: ModuleDef[]
    header: StructDef
    structs: StructDef[]
    messages: MessageDef[]
    constants: Record<string, number>
}

export type VizKindName =
    | 'NONE' | 'POINT' | 'TRACK' | 'RAY' | 'LINE' | 'CIRCULAR_AREA' | 'RECT_AREA'

export interface VizMapping {
    kind: VizKindName
    kindCode: number
    coordinates: 'POLAR' | 'CARTESIAN'
    repeats: boolean
    style?: {
        color?: string
        label?: string
        fillOpacity?: number
        dashed?: boolean
        emphasis?: boolean
        /** How long a mark of this type stays on the plan, in milliseconds. */
        persistenceMs?: number
    }
    note?: string
}

export interface VizCatalog {
    version: string
    conventions: Record<string, string>
    defaults: { maxRangeMeters?: number; rayLengthMeters?: number }
    mappings: Record<string, VizMapping>
}

export interface MessageSummary {
    id: number
    moduleId: number
    link: string | null
    msgId: number
    timestamp: number
    length: number
    type: string | null
    problem: string | null
    origin: string | null
    sent: boolean
    wallClock: number
    direction: Direction | null
    editable: boolean
    vizKind?: VizKindName
    preview: string
}

export interface MessageDetail extends MessageSummary {
    header: Record<string, number>
    payload: Record<string, unknown> | null
    decodable: boolean
}

export type SortKey = 'sequence' | 'timestamp' | 'type' | 'link' | 'length' | 'wallclock'
export type SortDir = 'asc' | 'desc'

/** A trace line is a message summary plus which way it went and when. */
export type TraceRow = Omit<MessageSummary, 'direction'> & {
    direction: 'IN' | 'OUT'
    wallClock: number
    deltaMillis: number
}

export interface TracePage {
    total: number
    returned: number
    items: TraceRow[]
}

export interface MessagePage {
    source?: string
    sort?: SortKey
    dir?: SortDir
    total: number
    filtered: number
    offset: number
    limit: number
    overflowed?: number
    items: MessageSummary[]
}

export type LinkState = 'DOWN' | 'LISTENING' | 'CONNECTED' | 'CLOSED' | 'FAILED'

export interface LinkStatus {
    name: string
    moduleId: number
    state: LinkState
    detail: string
    host: string
    port: number
    peer: string | null
    connectedAt: number
    bytesIn: number
    bytesOut: number
    messagesIn: number
    messagesOut: number
    writeStalls: number
    pendingInboundBytes: number
}

export type PlaybackStateName = 'IDLE' | 'RUNNING' | 'PAUSED' | 'FINISHED'

export interface PlaybackSnapshot {
    state: PlaybackStateName
    speed: number
    mode: 'TIMESTAMP' | 'MAX_RATE'
    sent: number
    sentBytes: number
    error: string | null
    startedAt: number
    finishedAt: number
    planned: number
    plannedBytes: number
    spanMillis: number
    epochMillis?: number
    virtualMillis: number
    /** Recorded-timeline milliseconds the pacer is behind schedule; 0 when keeping up. */
    lagMillis?: number
    planBuildMillis?: number
    tracks: { link: string; planned: number; sent: number; skipped: number }[]
}

export interface LogLine {
    seq: number
    t: number
    level: 'INFO' | 'WARN' | 'ERROR'
    source: string
    message: string
}

export interface LibraryItem {
    id: string
    name: string
    description: string
    tags: string[]
    typeName: string
    moduleId: number
    msgId: number
    length: number
    schemaVersion: string
    createdAt: number
    stale: boolean
    payload: Record<string, unknown>
}

export interface LinkTelemetry {
    name: string
    state: LinkState
    bytesIn: number
    bytesOut: number
    messagesIn: number
    messagesOut: number
    bytesInPerSecond: number
    bytesOutPerSecond: number
    messagesInPerSecond: number
    messagesOutPerSecond: number
    writeStalls: number
}

export interface Telemetry {
    links: LinkTelemetry[]
    captureMessages: number
    captureOverflowed: number
    playbackState: PlaybackStateName
    playbackSent: number
    playbackSentBytes: number
    vizSubscribers: number
    vizFramesSent: number
    vizFramesSkipped: number
    vizSamplesDropped: number
    vizStimulusThinned: number
}
