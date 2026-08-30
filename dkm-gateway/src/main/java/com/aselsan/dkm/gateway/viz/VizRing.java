package com.aselsan.dkm.gateway.viz;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Single-producer / single-consumer ring of fixed-size visualization samples.
 *
 * <p>This is the hand-off that keeps NFR-6 honest: the thread that just read
 * bytes off a socket writes a 48-byte record here and goes straight back to
 * reading. It never touches a WebSocket, never serialises JSON, and can never
 * be made to wait by a slow renderer. Exactly the receive-thread → queue →
 * worker split mock_r uses internally, for exactly the same reason.
 *
 * <p>One ring per producer, so the producer side needs no CAS at all: fill the
 * slot, then publish it with a single volatile store of the write sequence. A
 * consumer that reads that sequence first is guaranteed to see the filled slot.
 *
 * <p>When the ring is full the sample is dropped and counted rather than
 * blocking or growing. Dropping the odd frame of a live picture is correct;
 * stalling the socket read that produced it is not. The drop count is surfaced
 * in the UI so a dropped sample is never invisible.
 */
public final class VizRing {

    public static final int RECORD_BYTES = 48;

    // Record layout, little-endian, mirrored by the browser's DataView reads.
    private static final int OFF_SEQ = 0;       // u32
    private static final int OFF_MSG_ID = 4;    // u16
    private static final int OFF_LINK = 6;      // u8
    private static final int OFF_KIND = 7;      // u8
    private static final int OFF_TRACK = 8;     // u32
    private static final int OFF_FLAGS = 12;    // u32
    private static final int OFF_A = 16;        // f32 x4
    private static final int OFF_T = 32;        // f64
    private static final int OFF_E = 40;        // f32 x2

    public static final int FLAG_OUTPUT = 1;
    public static final int FLAG_EMPHASIS = 2;

    private final int capacity;
    private final int mask;
    private final ByteBuffer store;
    private final LongAdder dropped = new LongAdder();

    private volatile long writeSeq;
    private volatile long readSeq;

    public VizRing(int capacityRecords) {
        int capacityPow2 = Integer.highestOneBit(Math.max(capacityRecords, 2) - 1) << 1;
        this.capacity = capacityPow2;
        this.mask = capacityPow2 - 1;
        this.store = ByteBuffer.allocateDirect(capacityPow2 * RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    }

    public long droppedCount() {
        return dropped.sum();
    }

    /** Producer side. Returns false (and counts a drop) when the ring is full. */
    public boolean offer(int seq, int msgId, int link, VizKind kind, int trackId, int flags,
                         float a, float b, float c, float d, double t, float e, float f) {
        long write = writeSeq;
        if (write - readSeq >= capacity) {
            dropped.increment();
            return false;
        }
        int base = (int) (write & mask) * RECORD_BYTES;
        store.putInt(base + OFF_SEQ, seq);
        store.putShort(base + OFF_MSG_ID, (short) msgId);
        store.put(base + OFF_LINK, (byte) link);
        store.put(base + OFF_KIND, (byte) kind.code());
        store.putInt(base + OFF_TRACK, trackId);
        store.putInt(base + OFF_FLAGS, flags);
        store.putFloat(base + OFF_A, a);
        store.putFloat(base + OFF_A + 4, b);
        store.putFloat(base + OFF_A + 8, c);
        store.putFloat(base + OFF_A + 12, d);
        store.putDouble(base + OFF_T, t);
        store.putFloat(base + OFF_E, e);
        store.putFloat(base + OFF_E + 4, f);
        writeSeq = write + 1; // publishes everything above
        return true;
    }

    /**
     * Consumer side. Copies up to {@code maxRecords} records into {@code out}
     * and returns how many were copied.
     */
    public int drain(ByteBuffer out, int maxRecords) {
        long write = writeSeq;
        long read = readSeq;
        int available = (int) Math.min(write - read, maxRecords);
        available = Math.min(available, out.remaining() / RECORD_BYTES);
        for (int i = 0; i < available; i++) {
            int base = (int) ((read + i) & mask) * RECORD_BYTES;
            for (int b = 0; b < RECORD_BYTES; b += 8) {
                out.putLong(store.getLong(base + b));
            }
        }
        if (available > 0) {
            readSeq = read + available;
        }
        return available;
    }
}
