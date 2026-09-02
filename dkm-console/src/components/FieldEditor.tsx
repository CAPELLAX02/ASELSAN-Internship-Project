import { useEffect, useMemo, useRef, useState } from 'react'

import type { FieldDef, MessageDef, Schema } from '../api/types'
import { useT, type Translate } from '../i18n/useT'
import { Icon } from './Icon'
import { NumberField } from './NumberField'

/**
 * Generates a field editor from the schema (FR-30, G2).
 *
 * <p>There is not one line here that knows about DetectionReport, or beams, or
 * headings. Controls come from the declared type: an integer field gets its
 * range from its width and signedness, an enum field gets a select, a fixed
 * array gets a list whose live length is governed by its count field. A message
 * type added to the schema tomorrow gets a working editor today, which is what
 * NFR-1 is actually asking for.
 */

export type FieldPath = (string | number)[]

interface Props {
    schema: Schema
    type: MessageDef
    value: Record<string, unknown>
    readOnly: boolean
    onChange: (path: FieldPath, next: unknown) => void
}

export function FieldEditor({ schema, type, value, readOnly, onChange }: Props) {
    const t = useT()
    return (
        <div className="flex flex-col gap-2">
            {type.fields.map((field) => (
                <FieldRow
                    key={field.name}
                    schema={schema}
                    field={field}
                    path={[field.name]}
                    value={(value ?? {})[field.name]}
                    siblings={value ?? {}}
                    readOnly={readOnly}
                    onChange={onChange}
                    t={t}
                />
            ))}
            {type.fields.length === 0 && (
                <div className="text-ink-400 italic">{t('field.headerOnly')}</div>
            )}
        </div>
    )
}

interface RowProps {
    schema: Schema
    field: FieldDef
    path: FieldPath
    value: unknown
    siblings: Record<string, unknown>
    readOnly: boolean
    onChange: (path: FieldPath, next: unknown) => void
    t: Translate
    compact?: boolean
}

function FieldRow({ schema, field, path, value, siblings, readOnly, onChange, t, compact }: RowProps) {
    if (field.kind === 'PRIMITIVE') {
        return (
            <Labelled field={field} compact={compact}>
                <PrimitiveInput field={field} value={value} readOnly={readOnly} t={t}
                    onChange={(next) => onChange(path, next)} />
            </Labelled>
        )
    }

    if (field.kind === 'STRUCT' && field.struct) {
        return (
            <fieldset className="border border-ink-700  p-2">
                <legend className="px-1 text-ink-400">{field.name}<TypeTag field={field} /></legend>
                <div className="flex flex-col gap-2">
                    {field.struct.fields.map((child) => (
                        <FieldRow
                            key={child.name}
                            schema={schema}
                            field={child}
                            path={[...path, child.name]}
                            value={(value as Record<string, unknown>)?.[child.name]}
                            siblings={(value as Record<string, unknown>) ?? {}}
                            readOnly={readOnly}
                            onChange={onChange}
                            t={t}
                            compact
                        />
                    ))}
                </div>
            </fieldset>
        )
    }

    if (field.stringLike) {
        return (
            <Labelled field={field} compact={compact}>
                <input
                    className="field"
                    value={typeof value === 'string' ? value : ''}
                    maxLength={field.arrayLength - 1}
                    disabled={readOnly}
                    onChange={(e) => onChange(path, e.target.value)}
                />
            </Labelled>
        )
    }

    return (
        <ArrayField
            schema={schema} field={field} path={path} value={value}
            siblings={siblings} readOnly={readOnly} onChange={onChange} t={t}
        />
    )
}

/**
 * A fixed-capacity array with a live element count. The wire always carries
 * every declared slot, so the count is what says which of them mean anything --
 * showing all eight when three are live would be lying about the message, and
 * hiding the other five would lose the fact that they are still on the wire.
 */
function ArrayField({ schema, field, path, value, siblings, readOnly, onChange, t }: RowProps) {
    const elements = Array.isArray(value) ? value : []
    const declared = field.countField ? Number(siblings[field.countField] ?? 0) : elements.length
    const live = Math.max(0, Math.min(declared, field.arrayLength))

    const setCount = (next: number) => {
        if (!field.countField) return
        onChange([field.countField], Math.max(0, Math.min(next, field.arrayLength)))
    }

    return (
        <fieldset className="border border-ink-700  p-2">
            <legend className="px-1 text-ink-400 flex items-center gap-2">
                <span>{field.name}</span>
                <TypeTag field={field} />
                {field.countField && (
                    <span className="flex items-center gap-1 normal-case">
                        <button className="btn text-micro px-1.5 py-0" disabled={readOnly || live === 0}
                            onClick={() => setCount(live - 1)} title={t('field.reduceCount')}>&minus;</button>
                        <span className="text-ink-200">{live}</span>
                        <span className="text-ink-500">/ {field.arrayLength}</span>
                        <button className="btn text-micro px-1.5 py-0"
                            disabled={readOnly || live >= field.arrayLength}
                            onClick={() => setCount(live + 1)} title={t('field.addElement')}>+</button>
                    </span>
                )}
            </legend>

            <div className="flex flex-col gap-1.5">
                {Array.from({ length: field.arrayLength }, (_, index) => {
                    const inactive = index >= live
                    const struct = field.kind === 'STRUCT_ARRAY' ? field.struct : null
                    const body = struct
                        ? struct.fields.map((child) => (
                            <FieldRow
                                key={child.name}
                                schema={schema}
                                field={child}
                                path={[...path, index, child.name]}
                                value={(elements[index] as Record<string, unknown>)?.[child.name]}
                                siblings={(elements[index] as Record<string, unknown>) ?? {}}
                                readOnly={readOnly || inactive}
                                onChange={onChange}
                                t={t}
                                compact
                            />
                        ))
                        : (
                            <PrimitiveInput
                                field={field}
                                value={elements[index]}
                                readOnly={readOnly || inactive}
                                t={t}
                                onChange={(next) => onChange([...path, index], next)}
                            />
                        )

                    // A struct element is a card that folds: two fields fit
                    // today, but nothing about the schema says an element stays
                    // small, and eight open elements of a dozen fields each is a
                    // wall the operator has to scroll past to reach the message.
                    if (!struct) {
                        return (
                            <div
                                key={index}
                                className={`flex items-start gap-2 px-1.5 py-1 ${inactive ? 'opacity-35 bg-ink-950/60' : 'bg-ink-850/60'}`}
                                title={inactive
                                    ? t('field.inactiveSlot', { count: field.countField ?? live })
                                    : undefined}
                            >
                                <span className="w-6 shrink-0 text-ink-500 pt-1">{index}</span>
                                <div className="flex-1 flex flex-col gap-1.5">{body}</div>
                            </div>
                        )
                    }
                    return (
                        <ElementCard
                            key={index}
                            index={index}
                            inactive={inactive}
                            summary={summarise(struct, elements[index])}
                            title={inactive
                                ? t('field.inactiveSlot', { count: field.countField ?? live })
                                : undefined}
                            label={t('field.element', { index, name: struct.name })}
                            defaultOpen={!inactive && live <= 3}
                        >
                            {body}
                        </ElementCard>
                    )
                })}
            </div>
        </fieldset>
    )
}

/**
 * One element of a struct array, folded shut until it is wanted.
 *
 * <p>Closed it still says what it holds: the summary line carries the element's
 * first couple of values, so the operator can find the one they are looking for
 * without opening all eight. Height is animated from a measured height rather
 * than toggled, because a list that jumps loses the reader's place.
 */
function ElementCard({ index, inactive, summary, title, label, defaultOpen, children }: {
    index: number
    inactive: boolean
    summary: string
    title?: string
    label: string
    defaultOpen: boolean
    children: React.ReactNode
}) {
    const [open, setOpen] = useState(defaultOpen)
    const body = useRef<HTMLDivElement | null>(null)
    const [height, setHeight] = useState<number | undefined>(defaultOpen ? undefined : 0)

    useEffect(() => {
        const node = body.current
        if (!node) return
        if (!open) {
            setHeight(0)
            return
        }
        setHeight(node.scrollHeight)
        // Once it has finished opening the height is released, so a field that
        // grows -- an array inside the element -- is not clipped by a number
        // measured before it grew.
        const timer = window.setTimeout(() => setHeight(undefined), 200)
        return () => window.clearTimeout(timer)
    }, [open, children])

    return (
        <div
            className={`border ${inactive ? 'border-ink-800 opacity-35 bg-ink-950/60' : 'border-ink-700 bg-ink-850/60'}`}
            title={title}
        >
            <button
                type="button"
                className="w-full flex items-center gap-2 px-1.5 py-1 text-left hover:bg-ink-800/60 transition-colors"
                aria-expanded={open}
                onClick={() => setOpen((v) => !v)}
            >
                <Icon name="chevron" size={11}
                    className={`text-ink-500 transition-transform duration-150 ${open ? 'rotate-90' : ''}`} />
                <span className="w-6 shrink-0 text-ink-500">{index}</span>
                <span className="text-ink-400">{label}</span>
                {!open && summary && (
                    <span className="ml-auto num text-ink-500 truncate pl-2">{summary}</span>
                )}
            </button>
            <div
                className="overflow-hidden transition-[height] duration-150 ease-out"
                style={{ height: height === undefined ? undefined : `${height}px` }}
            >
                <div ref={body} className="flex flex-col gap-1.5 px-1.5 pb-1.5 pl-9">
                    {children}
                </div>
            </div>
        </div>
    )
}

/** The first couple of values, for the closed card's own line. */
function summarise(struct: { fields: FieldDef[] }, element: unknown): string {
    if (!element || typeof element !== 'object') return ''
    const bag = element as Record<string, unknown>
    return struct.fields.slice(0, 2)
        .map((f) => {
            const raw = bag[f.name]
            if (typeof raw === 'number') {
                return `${f.name} ${Number.isInteger(raw) ? raw : raw.toFixed(2)}`
            }
            return raw === undefined ? null : `${f.name} ${String(raw)}`
        })
        .filter(Boolean)
        .join('  ·  ')
}

function Labelled({ field, compact, children }: {
    field: FieldDef; compact?: boolean; children: React.ReactNode
}) {
    return (
        <label className={`flex ${compact ? 'items-center' : 'items-baseline'} gap-2`}>
            <span
                className="w-44 shrink-0 text-ink-300 truncate"
                title={[field.doc, `offset ${field.offset}, ${field.size} byte(s)`].filter(Boolean).join('\n')}
            >
                {field.name}
                <TypeTag field={field} />
            </span>
            <span className="flex-1 min-w-0">{children}</span>
        </label>
    )
}

function TypeTag({ field }: { field: FieldDef }) {
    const t = useT()
    return (
        <span className="ml-1.5 text-micro text-ink-500">
            {field.type}{field.array ? `[${field.arrayLength}]` : ''}
            {field.unit ? ` ${field.unit}` : ''}
            {field.correlationId ? ` · ${t('field.trackId')}` : ''}
        </span>
    )
}

function PrimitiveInput({ field, value, readOnly, onChange, t }: {
    field: FieldDef
    value: unknown
    readOnly: boolean
    onChange: (next: unknown) => void
    t: Translate
}) {
    const range = useMemo(() => integerRange(field), [field])

    if (field.type === 'bool') {
        return (
            <input
                type="checkbox"
                className="accent-signal"
                checked={Boolean(value)}
                disabled={readOnly}
                onChange={(e) => onChange(e.target.checked)}
            />
        )
    }

    if (field.enumValues && Object.keys(field.enumValues).length > 0) {
        const current = String(value ?? 0)
        const known = Object.keys(field.enumValues).includes(current)
        return (
            <div className="flex items-center gap-2">
                <select
                    className="field"
                    value={known ? current : '__other'}
                    disabled={readOnly}
                    onChange={(e) => e.target.value !== '__other' && onChange(Number(e.target.value))}
                >
                    {Object.entries(field.enumValues).map(([key, label]) => (
                        <option key={key} value={key}>{key} — {label}</option>
                    ))}
                    {/* A value the schema does not name is still a legal value on the
              wire, so it is shown rather than silently snapped to a known one. */}
                    {!known && <option value="__other">{t('field.notNamed', { value: current })}</option>}
                </select>
                <NumberField
                    className="field w-24"
                    integer
                    value={Number(value ?? 0)}
                    disabled={readOnly}
                    onChange={onChange}
                />
            </div>
        )
    }

    const isFloat = field.type === 'f32' || field.type === 'f64'
    return (
        <NumberField
            integer={!isFloat}
            step={isFloat ? 'any' : 1}
            min={range?.[0]}
            max={range?.[1]}
            value={Number.isFinite(Number(value)) ? Number(value) : 0}
            disabled={readOnly}
            title={range ? `${field.type} · ${range[0]} … ${range[1]}` : field.type}
            onChange={onChange}
        />
    )
}

/**
 * Bounds straight from the declared width. Beyond 2^53 the browser cannot
 * represent the limit exactly, so no bound is offered rather than a wrong one --
 * the gateway validates against the real range regardless (FR-5).
 */
function integerRange(field: FieldDef): [number, number] | null {
    if (!field.bits || field.type === 'f32' || field.type === 'f64') return null
    if (field.bits >= 53) return field.signed ? null : [0, Number.MAX_SAFE_INTEGER]
    return field.signed
        ? [-(2 ** (field.bits - 1)), 2 ** (field.bits - 1) - 1]
        : [0, 2 ** field.bits - 1]
}
