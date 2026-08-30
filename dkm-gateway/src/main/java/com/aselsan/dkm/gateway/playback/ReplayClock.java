package com.aselsan.dkm.gateway.playback;

/**
 * The one clock all three links are paced from (FR-13).
 *
 * <p>Immutable and swapped wholesale, so the pacer thread always reads a
 * self-consistent triple. That matters for FR-12: changing speed mid-run has to
 * change the <em>rate</em> from this instant onwards without the virtual
 * position jumping, which means re-anchoring reference and offset together --
 * exactly what a partially-updated clock would get wrong.
 *
 * @param referenceNanos     {@code System.nanoTime()} captured when this clock was anchored
 * @param anchorOffsetMillis the replay position, in recorded-timeline milliseconds, at that instant
 * @param speed              multiplier; 2.0 replays twice as fast
 */
public record ReplayClock(long referenceNanos, long anchorOffsetMillis, double speed) {

    private static final double NANOS_PER_MILLI = 1_000_000.0;

    /** Where the replay currently is, in recorded-timeline milliseconds. */
    public double virtualMillisAt(long nowNanos) {
        return anchorOffsetMillis + ((nowNanos - referenceNanos) / NANOS_PER_MILLI) * speed;
    }

    /** When a message recorded at {@code offsetMillis} should actually go out. */
    public long deadlineNanos(long offsetMillis) {
        return referenceNanos + (long) (((offsetMillis - anchorOffsetMillis) * NANOS_PER_MILLI) / speed);
    }

    /**
     * Re-anchors at {@code nowNanos} without moving the virtual position -- the
     * basis for both resume (FR-14) and a live speed change (FR-12).
     */
    public ReplayClock reanchor(long nowNanos, double newSpeed) {
        return new ReplayClock(nowNanos, (long) virtualMillisAt(nowNanos), newSpeed);
    }

    public static ReplayClock startingAt(long nowNanos, long offsetMillis, double speed) {
        return new ReplayClock(nowNanos, offsetMillis, speed);
    }
}
