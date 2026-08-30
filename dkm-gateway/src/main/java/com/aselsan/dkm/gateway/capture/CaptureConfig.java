package com.aselsan.dkm.gateway.capture;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/** Tuning for output capture. */
@ConfigMapping(prefix = "dkm.capture")
public interface CaptureConfig {

    /**
     * Ceiling on messages held in memory. Reaching it stops capture with a loud
     * error rather than quietly discarding the oldest -- for a tool whose job is
     * to show what the DKM actually said, losing messages without saying so is
     * the worst possible behaviour.
     */
    @WithDefault("2000000")
    int maxMessages();

    /** How often decoded output is pushed to the message list. */
    @WithDefault("100")
    long publishIntervalMillis();

    /** Messages decoded into a single list update; the rest are fetched on demand. */
    @WithDefault("200")
    int maxPublishedPerBatch();

    /** Mirror everything received to this file as it arrives, like mock_r's own MessageRecorder. */
    Optional<String> recordPath();
}
