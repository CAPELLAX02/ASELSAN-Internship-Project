package com.aselsan.dkm.gateway.playback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clock is where FR-12/FR-13/FR-14 actually live, so it is tested on its own
 * rather than only through a timing-sensitive end-to-end run.
 */
class ReplayClockTest {

    private static final long MS = 1_000_000L;

    @Test
    @DisplayName("at 1x, virtual time advances with real time")
    void realTime() {
        ReplayClock clock = ReplayClock.startingAt(0, 0, 1.0);
        assertEquals(0.0, clock.virtualMillisAt(0), 1e-9);
        assertEquals(250.0, clock.virtualMillisAt(250 * MS), 1e-6);
        assertEquals(250 * MS, clock.deadlineNanos(250));
    }

    @Test
    @DisplayName("speed scales the interval between messages, not their order")
    void speedScalesDeadlines() {
        ReplayClock double_ = ReplayClock.startingAt(0, 0, 2.0);
        assertEquals(500 * MS, double_.deadlineNanos(1000), "2x replays a 1000 ms offset in 500 ms");

        ReplayClock half = ReplayClock.startingAt(0, 0, 0.5);
        assertEquals(2000 * MS, half.deadlineNanos(1000));
    }

    @Test
    @DisplayName("FR-12: changing speed mid-run does not move the replay position")
    void speedChangeIsContinuous() {
        ReplayClock clock = ReplayClock.startingAt(0, 0, 1.0);
        long now = 400 * MS;
        double before = clock.virtualMillisAt(now);
        assertEquals(400.0, before, 1e-6);

        ReplayClock faster = clock.reanchor(now, 4.0);
        assertEquals(before, faster.virtualMillisAt(now), 1.0,
                "the position at the instant of the change has to be the same on both sides of it");
        // From here on, 100 ms of real time covers 400 ms of recorded time.
        assertEquals(800.0, faster.virtualMillisAt(now + 100 * MS), 1e-6);
    }

    @Test
    @DisplayName("FR-14: resuming re-derives send times from a fresh reference instant")
    void resumeReanchors() {
        // A run paused after the message at offset 1000, resumed a long wall-clock
        // time later. The gap the operator spent editing must not be replayed.
        ReplayClock resumed = ReplayClock.startingAt(9_000_000 * MS, 1000, 1.0);
        assertEquals(9_000_000L * MS, resumed.deadlineNanos(1000),
                "the next pending message goes out immediately on resume");
        assertEquals(9_000_500L * MS, resumed.deadlineNanos(1500),
                "and the ones after it keep their recorded spacing");
    }

    @Test
    @DisplayName("all links share one clock, so cross-link ordering survives any speed")
    void crossLinkOrderingHolds() {
        // RSM announces a beam at 2500 ms; RSP's detection referencing it is at
        // 3500 ms. Whatever the speed, the beam has to come first -- this is the
        // ordering three independently-paced streams would break.
        for (double speed : new double[]{0.1, 1.0, 25.0, 1000.0}) {
            ReplayClock clock = ReplayClock.startingAt(0, 0, speed);
            long beam = clock.deadlineNanos(2500);
            long detection = clock.deadlineNanos(3500);
            assertTrue(beam < detection,
                    "at " + speed + "x the BeamReport must still precede the DetectionReport");
        }
    }
}
