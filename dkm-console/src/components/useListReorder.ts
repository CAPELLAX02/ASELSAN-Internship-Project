import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Reordering a fixed-row list by dragging, built on pointer events.
 *
 * <p>Two things decide whether a drag feels solid, and the HTML5 drag-and-drop
 * API gets both wrong for a list like this one.
 *
 * <p>The first is how the target slot is chosen. The obvious way -- ask which
 * row is under the cursor -- cannot work here, because the rows move: opening a
 * gap shifts every row between the source and the target by one row height, so
 * the element under the cursor changes as a consequence of the answer, which
 * changes the answer. The result oscillates near every boundary. Here the slot
 * is arithmetic instead: the pointer's position in the list's own coordinates,
 * divided by the row height. Nothing is hit-tested, so nothing can move out
 * from under the question.
 *
 * <p>The second is where a drop is allowed to land. `drop` only fires on an
 * element that accepted the drag, so releasing over a gap, over the padding
 * below the last row, or over a row that is not itself draggable does nothing
 * at all -- the drag silently evaporates. Pointer events have no such notion:
 * the gesture belongs to the pointer from the moment it is captured until it is
 * released, wherever that happens.
 *
 * <p>It also buys the things the native API cannot give: a real lifted row
 * rather than the browser's screenshot of one, auto-scrolling at the edges of a
 * long list, Escape to cancel, and a drag that survives the list being
 * windowed underneath it.
 */

/** How far the pointer must travel before this becomes a drag and not a click. */
const THRESHOLD_PX = 4

/** How close to an edge the pointer must come before the list scrolls itself. */
const EDGE_PX = 32

/** Fastest edge scroll, in pixels per second. */
const MAX_SCROLL_SPEED = 900

export interface ReorderState {
    id: number
    /** Where the row started, as an index into the whole page. */
    from: number
    /** Where it would land if released now. */
    to: number
    /** The lifted row's top, in the scroll container's content coordinates. */
    y: number
}

interface Session extends ReorderState {
    pointerId: number
    /** Distance from the row's top to where the pointer grabbed it. */
    grab: number
    startClientY: number
    started: boolean
    pointerClientY: number
}

export function useListReorder({ count, rowHeight, scrollRef, enabled, floor, onCommit }: {
    count: number
    rowHeight: number
    scrollRef: React.RefObject<HTMLDivElement | null>
    enabled: boolean
    /**
     * The first slot a row may be dropped into.
     *
     * <p>Everything above it has already gone out. Reordering across that line
     * would rewrite the recorded time of messages the DKM has already received,
     * which is not an edit but a falsification -- so the row simply stops there,
     * and the gesture never asks for something that would have to be refused.
     */
    floor: number
    onCommit: (id: number, toIndex: number) => void
}) {
    const [state, setState] = useState<ReorderState | null>(null)
    const session = useRef<Session | null>(null)
    /** Set while a drag is finishing, so the click it ends with is not a select. */
    const suppressClick = useRef(false)
    const scrolling = useRef(0)

    // The live values, read by handlers that outlive the render they were made
    // in. Written after commit rather than during render: a ref mutated while
    // rendering is a value React never agreed to, and the only reader here is an
    // event that cannot fire until the commit is done.
    const latest = useRef({ count, rowHeight, floor, onCommit })
    useEffect(() => {
        latest.current = { count, rowHeight, floor, onCommit }
    })

    const stop = useCallback(() => {
        session.current = null
        setState(null)
        if (scrolling.current) {
            cancelAnimationFrame(scrolling.current)
            scrolling.current = 0
        }
    }, [])

    /** Recomputes the target slot and the lifted row's position from the pointer. */
    const track = useCallback((clientY: number) => {
        const live = session.current
        const element = scrollRef.current
        if (!live || !element) return
        const { count: total, rowHeight: height, floor: lowest } = latest.current

        const box = element.getBoundingClientRect()
        const contentY = clientY - box.top + element.scrollTop
        const top = contentY - live.grab
        // Rounding rather than flooring: the slot is the one the row's own top is
        // nearest to, which is what the eye is judging as it moves.
        const to = Math.max(lowest, Math.min(total - 1, Math.round(top / height)))

        live.pointerClientY = clientY
        live.to = to
        live.y = Math.max(lowest * height, Math.min(top, (total - 1) * height))
        setState({ id: live.id, from: live.from, to, y: live.y })
    }, [scrollRef])

    /**
     * Scrolls the list while the pointer rests near an edge.
     *
     * <p>Speed rises with how far into the edge zone the pointer is, so a small
     * overshoot creeps and a deliberate one moves. Without this a five-hundred
     * row page can only be reordered within one screen.
     */
    const edgeScroll = useCallback(function step() {
        const live = session.current
        const element = scrollRef.current
        if (!live || !element) {
            scrolling.current = 0
            return
        }
        const box = element.getBoundingClientRect()
        const fromTop = live.pointerClientY - box.top
        const fromBottom = box.bottom - live.pointerClientY

        let velocity = 0
        if (fromTop < EDGE_PX) {
            velocity = -MAX_SCROLL_SPEED * Math.min(1, (EDGE_PX - fromTop) / EDGE_PX)
        } else if (fromBottom < EDGE_PX) {
            velocity = MAX_SCROLL_SPEED * Math.min(1, (EDGE_PX - fromBottom) / EDGE_PX)
        }

        if (velocity !== 0) {
            const before = element.scrollTop
            element.scrollTop = before + velocity / 60
            // The pointer has not moved, but the content under it has, so the
            // target slot has to be recomputed against the new scroll offset.
            if (element.scrollTop !== before) track(live.pointerClientY)
        }
        // `step`, not `edgeScroll`: a named function expression recurses onto
        // itself, so the loop cannot be left calling a closure from a render
        // that has since been replaced.
        scrolling.current = requestAnimationFrame(step)
    }, [scrollRef, track])

    const begin = useCallback((
        event: React.PointerEvent<HTMLElement>, id: number, index: number,
    ) => {
        if (!enabled || event.button !== 0 || session.current) return
        const element = scrollRef.current
        if (!element) return
        const { rowHeight: height } = latest.current

        // Where the row belongs, not where it currently appears. The two differ
        // whenever a settle animation from the previous drag is still running:
        // measuring the rendered box would fold that leftover transform into the
        // grab offset, and every slot the new drag computes would be one row out.
        // This was the whole of the "sometimes it works" -- a second drag begun
        // within the settle window landed wrong, a slower one landed right.
        const box = element.getBoundingClientRect()
        const logicalTop = box.top - element.scrollTop + index * height
        const grab = Math.max(0, Math.min(height, event.clientY - logicalTop))

        session.current = {
            id,
            from: index,
            to: index,
            y: index * height,
            pointerId: event.pointerId,
            grab,
            startClientY: event.clientY,
            started: false,
            pointerClientY: event.clientY,
        }
    }, [enabled, scrollRef])

    useEffect(() => {
        const onMove = (event: PointerEvent) => {
            const live = session.current
            if (!live || event.pointerId !== live.pointerId) return
            if (!live.started) {
                if (Math.abs(event.clientY - live.startClientY) < THRESHOLD_PX) return
                live.started = true
                suppressClick.current = true
                if (!scrolling.current) scrolling.current = requestAnimationFrame(edgeScroll)
            }
            // Once this is a drag it owns the pointer, so text selection and the
            // list's own scrolling stay out of the way.
            event.preventDefault()
            track(event.clientY)
        }

        const onUp = (event: PointerEvent) => {
            const live = session.current
            if (!live || event.pointerId !== live.pointerId) return
            // Where the pointer is released is where the row goes. Reading the
            // last pointermove instead would be a guess: moves can be coalesced
            // or dropped under load, and the release then lands the row wherever
            // the last surviving move happened to be -- short of where the hand
            // actually let go.
            if (live.started) track(event.clientY)
            const committed = live.started && live.to !== live.from
            const { id, to, started } = live
            stop()
            if (committed) latest.current.onCommit(id, to)
            // Released without moving: the row was clicked, not dragged.
            if (!started) suppressClick.current = false
        }

        const onCancel = () => { if (session.current) stop() }

        const onKey = (event: KeyboardEvent) => {
            if (event.key === 'Escape' && session.current) {
                event.preventDefault()
                stop()
            }
        }

        window.addEventListener('pointermove', onMove, { passive: false })
        window.addEventListener('pointerup', onUp)
        window.addEventListener('pointercancel', onCancel)
        window.addEventListener('keydown', onKey)
        return () => {
            window.removeEventListener('pointermove', onMove)
            window.removeEventListener('pointerup', onUp)
            window.removeEventListener('pointercancel', onCancel)
            window.removeEventListener('keydown', onKey)
            if (scrolling.current) cancelAnimationFrame(scrolling.current)
        }
    }, [edgeScroll, stop, track])

    /** How far a row at this index moves to make room. */
    const shiftFor = useCallback((index: number): number => {
        if (!state || index === state.from) return 0
        if (state.to > state.from && index > state.from && index <= state.to) return -rowHeight
        if (state.to < state.from && index >= state.to && index < state.from) return rowHeight
        return 0
    }, [state, rowHeight])

    /** Whether the click that ends this gesture should be ignored. */
    const takeClickSuppression = useCallback(() => {
        const suppressed = suppressClick.current
        suppressClick.current = false
        return suppressed
    }, [])

    return { state, begin, shiftFor, takeClickSuppression }
}
