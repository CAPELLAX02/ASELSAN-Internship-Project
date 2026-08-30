import type { PickResult } from '../gl/Scene'
import { hasTranslation } from '../i18n'
import { useT } from '../i18n/useT'
import { count, degrees, metres, seconds } from './format'

/**
 * The values behind whatever the cursor is over.
 *
 * <p>A plan view is a good way to see *where* something is and a poor way to
 * see *what* it is. Hovering closes that gap without sending the operator to
 * the message list: range and bearing for a mark, both bounds for an area, the
 * id and sample count for a track.
 *
 * <p>Deliberately small and factual. It follows the cursor, never covers what
 * it describes, and flips side near an edge so it is always fully on screen.
 */

export interface TooltipTarget {
    pick: PickResult
    /** Cursor position in CSS pixels, relative to the plan view. */
    screenX: number
    screenY: number
    typeName: string | null
    linkName: string
    color: string
}

const WIDTH = 210

export function PlanTooltip({ target, bounds }: {
    target: TooltipTarget
    bounds: { width: number; height: number }
}) {
    const t = useT()
    const { pick } = target

    const labelKey = target.typeName ? `viz.label.${target.typeName}` : ''
    const title = hasTranslation(labelKey)
        ? t(labelKey)
        : target.typeName ?? `msg_id ${pick.msgId}`

    const rows: [string, string][] = []
    let footnote: string | null = null

    if (pick.kind === 'point' || pick.kind === 'track') {
        rows.push([t('tip.range'), metres(pick.distance)])
        rows.push([t('tip.bearing'), degrees(pick.heading)])
        rows.push([t('tip.position'), `${metres(pick.x)}, ${metres(pick.y)}`])
        if (pick.vx !== 0 || pick.vy !== 0) {
            rows.push([t('tip.speed'), `${count(Math.round(Math.hypot(pick.vx, pick.vy)))} m/s`])
            rows.push([t('tip.velocity'), `${count(Math.round(pick.vx))}, ${count(Math.round(pick.vy))}`])
        }
        if (pick.kind === 'track') {
            rows.push([t('tip.track'), `#${pick.trackId}`])
            rows.push([t('tip.points'), String(pick.points)])
            footnote = t('tip.trackHint')
        }
        rows.push([t('tip.age'), t('tip.seconds', { value: seconds(pick.ageMs) })])
    } else if (pick.kind === 'sector') {
        rows.push([t('tip.rangeBand'), `${metres(pick.r0)} … ${metres(pick.r1)}`])
        rows.push([t('tip.bearingBand'), `${degrees(pick.h0)} … ${degrees(pick.h1)}`])
        footnote = t('tip.excludes')
    } else if (pick.kind === 'rect') {
        rows.push(['X', `${metres(pick.x0)} … ${metres(pick.x1)}`])
        rows.push(['Y', `${metres(pick.y0)} … ${metres(pick.y1)}`])
        rows.push([t('tip.width'), metres(pick.x1 - pick.x0)])
        rows.push([t('tip.height'), metres(pick.y1 - pick.y0)])
        footnote = t('tip.includes')
    } else if (pick.kind === 'ray') {
        rows.push([t('tip.bearing'), degrees(pick.heading)])
        rows.push([t('tip.range'), metres(pick.length)])
    }

    // Flip to the other side of the cursor near an edge rather than being clipped.
    const flipX = target.screenX + WIDTH + 24 > bounds.width
    const estimatedHeight = 58 + rows.length * 18 + (footnote ? 34 : 0)
    const flipY = target.screenY + estimatedHeight + 24 > bounds.height

    return (
        <div
            className="absolute z-20 pointer-events-none rounded-md border border-ink-600
                 bg-ink-900/97 shadow-xl backdrop-blur-sm px-2.5 py-2"
            style={{
                width: WIDTH,
                left: flipX ? target.screenX - WIDTH - 14 : target.screenX + 14,
                top: flipY ? target.screenY - estimatedHeight - 10 : target.screenY + 14,
            }}
        >
            <div className="flex items-center gap-1.5 pb-1.5 mb-1.5 border-b border-ink-700">
                <span className="w-2 h-2 rounded-sm shrink-0" style={{ background: target.color }} />
                <span className="text-ink-100 font-semibold truncate">{title}</span>
            </div>

            <div className="flex items-center justify-between text-mini text-ink-500 mb-1">
                <span>{target.linkName}</span>
                <span className={pick.output ? 'text-signal' : 'text-ink-400'}>
                    {pick.output ? t('tip.output') : t('tip.stimulus')}
                </span>
            </div>

            <table className="w-full">
                <tbody>
                    {rows.map(([label, value]) => (
                        <tr key={label}>
                            <td className="text-ink-400 pr-2 py-px whitespace-nowrap align-top">{label}</td>
                            <td className="text-ink-100 py-px text-right whitespace-nowrap num">{value}</td>
                        </tr>
                    ))}
                </tbody>
            </table>

            {footnote && (
                <div className="mt-1.5 pt-1.5 border-t border-ink-700 text-mini text-ink-400 leading-snug">
                    {footnote}
                </div>
            )}
        </div>
    )
}
