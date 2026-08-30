package com.aselsan.dkm.gateway.playback;

/** FR-11: stop and pause are genuinely different states, not one flag. */
public enum PlaybackState {
    /** Nothing planned or everything reset. The whole set is editable. */
    IDLE,
    /** The pacer is running against the shared replay clock. */
    RUNNING,
    /** Sending suspended, connections and progress retained; pending messages are editable (FR-8). */
    PAUSED,
    /** Every planned message has gone out. */
    FINISHED
}
