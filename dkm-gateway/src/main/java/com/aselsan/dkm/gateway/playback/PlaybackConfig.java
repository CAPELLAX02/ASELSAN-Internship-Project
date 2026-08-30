package com.aselsan.dkm.gateway.playback;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Tuning for the replay pacer. */
@ConfigMapping(prefix = "dkm.playback")
public interface PlaybackConfig {

    /**
     * Largest slice handed to a socket in one write. Bigger means fewer
     * syscalls; too big and one link can monopolise its event loop while
     * another link's messages sit past their deadline.
     */
    @WithDefault("262144")
    int maxBatchBytes();

    /** Most messages coalesced into a single write, regardless of byte size. */
    @WithDefault("4096")
    int maxBatchMessages();

    /**
     * Below this, the pacer spins instead of parking. Park granularity is around
     * a millisecond on a general-purpose OS, which would smear millisecond-spaced
     * message timing badly.
     */
    @WithDefault("2000000")
    long spinThresholdNanos();

    /** How long the pacer waits for a full write queue to drain before giving up on that write. */
    @WithDefault("5000")
    long drainTimeoutMillis();

    /** Minimum gap between "messages sent" progress events, so the UI is never the bottleneck. */
    @WithDefault("100")
    long progressIntervalMillis();
}
