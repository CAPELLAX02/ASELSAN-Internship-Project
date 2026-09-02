package com.aselsan.dkm.gateway.net;

import com.aselsan.dkm.gateway.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Splits a TCP byte stream into messages using nothing but {@code msg_length}
 * (§4) -- no per-type size table, so a message type this build has never heard
 * of still frames correctly and can be captured and reported rather than
 * corrupting everything after it.
 *
 * <p>Not thread-safe by design: one instance per link, touched only from that
 * link's Vert.x event loop.
 *
 * <p>The common case -- a read that contains one or more whole messages and
 * nothing left over -- is handled without copying a single byte: frames are
 * emitted as offsets into the incoming buffer. Only a trailing partial message
 * is copied into the carry buffer, and only until its remainder arrives.
 */
public final class FrameSplitter {

    /**
     * The stream desynchronised. TCP has no delimiters here, so there is no
     * honest way to resynchronise -- the only correct response is to report it
     * and tear the link down (NFR-5).
     */
    public static final class DesyncException extends RuntimeException {
        public DesyncException(String message) {
            super(message);
        }
    }

    private final Wire wire;
    private final int headerSize;
    private final int msgLengthOffset;
    private final int msgLengthWidth;
    private final int maxMessageBytes;

    private ByteBuf carry;

    /**
     * @param msgLengthWidth the declared width of {@code msg_length}, which is
     *                       not necessarily the target's word size: an interface
     *                       may carry the length in four bytes on a 64-bit build.
     */
    public FrameSplitter(Wire wire, int headerSize, int msgLengthOffset, int msgLengthWidth, int maxMessageBytes) {
        this.wire = wire;
        this.headerSize = headerSize;
        this.msgLengthOffset = msgLengthOffset;
        this.msgLengthWidth = msgLengthWidth;
        this.maxMessageBytes = maxMessageBytes;
    }

    /**
     * The first bytes of what the peer actually sent, grouped in eights.
     *
     * <p>Grouped rather than run together because the reader is comparing this
     * against a C++ struct, and the question is always which field starts where.
     */
    static String hexPreview(ByteBuf src, int base, int readable) {
        int count = Math.min(readable, 32);
        StringBuilder out = new StringBuilder(count * 3 + 8);
        for (int i = 0; i < count; i++) {
            if (i > 0 && i % 8 == 0) {
                out.append(" | ");
            } else if (i > 0) {
                out.append(' ');
            }
            out.append(String.format("%02x", src.getByte(base + i) & 0xff));
        }
        if (readable > count) {
            out.append(" ...");
        }
        return out.toString();
    }

    public void feed(ByteBuf in, FrameHandler handler) {
        ByteBuf src;
        boolean fromCarry;
        if (carry != null && carry.isReadable()) {
            carry.writeBytes(in);
            src = carry;
            fromCarry = true;
        } else {
            src = in;
            fromCarry = false;
        }

        while (true) {
            int readable = src.readableBytes();
            if (readable < headerSize) {
                break;
            }
            int base = src.readerIndex();
            long declared = wire.readUnsigned(src, base + msgLengthOffset, msgLengthWidth);
            if (declared < headerSize || declared > maxMessageBytes) {
                // The bytes themselves, because the number alone cannot tell the
                // two causes apart. A stream that genuinely desynchronised looks
                // like noise here; a header the schema describes wrongly looks
                // like a perfectly sensible message read from the wrong offsets,
                // and the only way to see which is to put the peer's own bytes
                // next to the struct it was supposed to have sent.
                throw new DesyncException("msg_length = " + Long.toUnsignedString(declared)
                        + " is not a plausible message size (header is " + headerSize
                        + " bytes, read " + msgLengthWidth + " bytes at offset " + msgLengthOffset
                        + ", ceiling is " + maxMessageBytes
                        + ") -- the stream is out of sync or the peer's data model differs."
                        + " First bytes on the wire: " + hexPreview(src, base, readable));
            }
            int length = (int) declared;
            if (readable < length) {
                break;
            }
            handler.onFrame(src, base, length);
            src.skipBytes(length);
        }

        if (fromCarry) {
            carry.discardSomeReadBytes();
        } else if (in.isReadable()) {
            stash(in);
        }
    }

    private void stash(ByteBuf in) {
        int remaining = in.readableBytes();
        if (carry == null) {
            carry = Unpooled.buffer(Math.max(remaining, headerSize * 4));
        }
        carry.writeBytes(in, remaining);
    }

    /** Bytes held back waiting for the rest of a partial message. */
    public int pendingBytes() {
        return carry == null ? 0 : carry.readableBytes();
    }

    public void reset() {
        if (carry != null) {
            carry.release();
            carry = null;
        }
    }
}
