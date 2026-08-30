package com.aselsan.dkm.gateway.viz;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Tuning for the live visualization path. */
@ConfigMapping(prefix = "dkm.viz")
public interface VizConfig {

    /**
     * How often a visualization frame is pushed to the browser. 16 ms lines up
     * with a 60 Hz display; anything faster only produces frames the browser
     * throws away. The wire-to-pixel budget (NFR-6) is this plus one animation
     * frame, so roughly 30 ms typical and bounded well under 100 ms.
     */
    @WithDefault("16")
    long frameIntervalMillis();

    /** Samples buffered per producer before the oldest are lost. */
    @WithDefault("65536")
    int ringCapacity();

    /** Ceiling on one frame, so a burst cannot produce a multi-megabyte WebSocket message. */
    @WithDefault("4096")
    int maxRecordsPerFrame();

    /**
     * Stimulus samples visualised per frame interval. Output is comfortably
     * sampled in full, but stimulus can run orders of magnitude faster than any
     * display; past this budget the picture is thinned rather than allowed to
     * slow the pacer down. The count of what was thinned is reported.
     */
    @WithDefault("2048")
    int stimulusBudgetPerFrame();

    /** Frames a single browser may fall behind before its frames start being skipped. */
    @WithDefault("4")
    int maxInFlightFrames();
}
