package com.aselsan.dkm.gateway.net;

import io.netty.buffer.ByteBuf;

/**
 * Receives every complete message the DKM sends back, on the link's event loop.
 *
 * <p>Implementations must return promptly and must not block: this call sits
 * directly between {@code recv()} and the next {@code recv()}, mirroring
 * mock_r's own receive-thread → MessageQueue → processing-thread split
 * (NFR-6). The buffer is only valid for the duration of the call.
 */
public interface InboundSink {
    void onInbound(Link link, ByteBuf buf, int offset, int length);
}
