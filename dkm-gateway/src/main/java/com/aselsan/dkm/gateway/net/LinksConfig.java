package com.aselsan.dkm.gateway.net;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Map;
import java.util.Optional;

/** TCP topology and socket tuning for the three peer links. */
@ConfigMapping(prefix = "dkm.links")
public interface LinksConfig {

    /** Interface to bind. 0.0.0.0 so a DKM on another host can reach us. */
    @WithDefault("0.0.0.0")
    String host();

    /** Per-module port override, e.g. {@code dkm.links.port.RSP=5001}. Falls back to the schema's port. */
    Map<String, Integer> port();

    /**
     * Bytes Vert.x will buffer for a link before {@code writeQueueFull()} goes
     * true. Sized for the 1 GB/s stimulus target: at that rate a 64 KB default
     * would report "full" ~16000 times a second and turn the pacer into a
     * ping-pong with the event loop.
     */
    @WithDefault("16777216")
    int writeQueueMaxBytes();

    /** SO_SNDBUF / SO_RCVBUF. 0 leaves the OS default. */
    @WithDefault("4194304")
    int sendBufferBytes();

    @WithDefault("1048576")
    int receiveBufferBytes();

    /**
     * Ceiling on a single message's {@code msg_length}. A stream with no
     * delimiters cannot be resynchronised, so a length past this is treated as
     * a fatal framing error rather than a 4 GB allocation.
     */
    @WithDefault("16777216")
    int maxMessageBytes();

    /** Optional bind-time backlog. */
    Optional<Integer> acceptBacklog();
}
