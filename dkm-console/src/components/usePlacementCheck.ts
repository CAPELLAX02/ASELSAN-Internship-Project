import { useCallback } from 'react'

import { api } from '../api/client'
import type { MessageSummary } from '../api/types'

/**
 * What an insert at this offset would actually do to the order.
 *
 * <p>The stimulus list is always in ascending time, because that is the order it
 * will be sent in and a list that reads in a different order than it sends is
 * worse than no list. So "insert after the selected message" is a request about
 * position, while the offset is a statement about time -- and the two disagree
 * whenever the gap is bigger than the gap that is already there.
 *
 * <p>Rather than silently honouring one and quietly breaking the other, the
 * caller is told which it is about to get. That is the operator's call to make:
 * the timing may well be the point, and the position merely where they happened
 * to be standing.
 */
export type Placement =
    /** The offset runs backwards. Never allowed: it would break the invariant. */
    | { verdict: 'negative'; offsetMillis: number }
    /** Lands right after the anchor, as asked. */
    | { verdict: 'ok'; timestamp: number }
    /**
     * Lands later than the anchor's current successor, so it goes further down
     * the list than "immediately after" implies.
     */
    | {
        verdict: 'reordered'
        timestamp: number
        /** The successor it would jump over. */
        nextTimestamp: number
        /** The largest offset that would still land it immediately after. */
        maxOffsetMillis: number
    }

export function usePlacementCheck() {
    return useCallback(async (
        anchorIndex: number | null,
        offsetMillis: number,
    ): Promise<Placement> => {
        if (offsetMillis < 0) {
            return { verdict: 'negative', offsetMillis }
        }
        // Appending to the end cannot displace anything.
        if (anchorIndex === null) {
            return { verdict: 'ok', timestamp: 0 }
        }

        // The anchor and whatever currently follows it, straight from the
        // gateway: the panel may be showing a different page, or none.
        const page = await api.sessionMessages({ offset: anchorIndex, limit: 2 })
        const rows = page.items as MessageSummary[]
        const anchor = rows[0]
        if (!anchor) {
            return { verdict: 'ok', timestamp: 0 }
        }
        const timestamp = anchor.timestamp + offsetMillis
        const next = rows[1]
        if (!next || timestamp <= next.timestamp) {
            return { verdict: 'ok', timestamp }
        }
        return {
            verdict: 'reordered',
            timestamp,
            nextTimestamp: next.timestamp,
            maxOffsetMillis: next.timestamp - anchor.timestamp,
        }
    }, [])
}
