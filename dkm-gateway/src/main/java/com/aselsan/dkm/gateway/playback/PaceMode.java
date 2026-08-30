package com.aselsan.dkm.gateway.playback;

/** How the pacer decides when the next message goes out. */
public enum PaceMode {
    /**
     * The real one (FR-13): every link is paced from one shared clock derived
     * from each message's recorded timestamp. Three independently-paced streams
     * would let a fast link race ahead of a slow one and break the cross-link
     * dependencies that actually exist -- a DetectionReport whose beam_id has
     * not been announced by an RSM BeamReport yet is silently dropped by the DKM.
     */
    TIMESTAMP,
    /**
     * Ignore recorded timing and push as hard as backpressure allows. For
     * throughput characterisation and for soak-testing the DKM's input path;
     * cross-link ordering still holds, because messages still go out in
     * timestamp order within each link.
     */
    MAX_RATE
}
