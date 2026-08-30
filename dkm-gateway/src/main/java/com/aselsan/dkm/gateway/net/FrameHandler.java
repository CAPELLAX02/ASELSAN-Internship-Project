package com.aselsan.dkm.gateway.net;

import io.netty.buffer.ByteBuf;

/**
 * Called on a link's event loop for every complete message split out of the
 * incoming byte stream.
 *
 * <p>The {@link ByteBuf} handed in is a live view of the receive buffer and is
 * only valid for the duration of the call -- an implementation that wants to
 * keep the bytes must copy them. That is deliberate: it keeps the receive path
 * to zero allocations for implementations that only need to read a few fields.
 */
@FunctionalInterface
public interface FrameHandler {
    void onFrame(ByteBuf buf, int offset, int length);
}
