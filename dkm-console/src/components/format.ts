import type { Lang } from '../i18n'

/**
 * Number formatting that follows the interface language.
 *
 * <p>"1.234,5" and "1,234.5" are the same number to a machine and two different
 * numbers to a reader glancing at a column. A Turkish interface that prints
 * English decimals is the kind of detail that quietly undermines everything
 * around it, so the separators come from the active language rather than from
 * whatever the browser happens to default to.
 *
 * <p>The locale lives in a module variable rather than being threaded through
 * every call site: it changes about once a session, it is read from dozens of
 * places, and the alternative is a parameter on every formatting call for no
 * gain. {@link setNumberLocale} is called by the store whenever the language
 * changes.
 */

let locale: string = 'en-US'
const cache = new Map<string, Intl.NumberFormat>()

export function setNumberLocale(lang: Lang) {
    locale = lang === 'tr' ? 'tr-TR' : 'en-US'
    cache.clear()
}

function formatter(minimumFractionDigits: number, maximumFractionDigits: number): Intl.NumberFormat {
    const key = `${minimumFractionDigits}:${maximumFractionDigits}`
    let existing = cache.get(key)
    if (!existing) {
        existing = new Intl.NumberFormat(locale, { minimumFractionDigits, maximumFractionDigits })
        cache.set(key, existing)
    }
    return existing
}

function decimal(value: number, digits: number): string {
    if (!Number.isFinite(value)) return '-'
    return formatter(digits, digits).format(value)
}

export function bytes(value: number): string {
    if (!Number.isFinite(value)) return '-'
    const units = ['B', 'KB', 'MB', 'GB', 'TB']
    let scaled = value
    let unit = 0
    while (scaled >= 1024 && unit < units.length - 1) {
        scaled /= 1024
        unit++
    }
    return `${decimal(scaled, scaled >= 100 || unit === 0 ? 0 : 1)} ${units[unit]}`
}

export function rate(bytesPerSecond: number): string {
    return `${bytes(bytesPerSecond)}/s`
}

export function count(value: number): string {
    if (!Number.isFinite(value)) return '-'
    return formatter(0, 0).format(value)
}

export function clockTime(epochMillis: number): string {
    if (!epochMillis) return '--:--:--'
    const date = new Date(epochMillis)
    return date.toTimeString().slice(0, 8) + '.' + String(date.getMilliseconds()).padStart(3, '0')
}

export function duration(millis: number): string {
    if (!Number.isFinite(millis) || millis < 0) return decimal(0, 2) + 's'
    if (millis < 10_000) return `${decimal(millis / 1000, 2)}s`
    const seconds = Math.floor(millis / 1000)
    const minutes = Math.floor(seconds / 60)
    return `${minutes}:${String(seconds % 60).padStart(2, '0')}`
}

export function metres(value: number): string {
    if (!Number.isFinite(value)) return '-'
    if (Math.abs(value) >= 10_000) return `${decimal(value / 1000, 1)} km`
    if (Math.abs(value) >= 1000) return `${decimal(value / 1000, 2)} km`
    return `${decimal(value, 0)} m`
}

export function degrees(radians: number): string {
    return `${decimal((radians * 180) / Math.PI, 1)}°`
}

/** Seconds with one decimal, in the active locale. */
export function seconds(millis: number): string {
    return decimal(millis / 1000, 1)
}

/** A plain number with a fixed number of decimals, in the active locale. */
export function number(value: number, digits: number): string {
    return decimal(value, digits)
}
