package com.aselsan.dkm.gateway.model;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Off-heap storage for a set of messages, as a list of fixed-size segments that
 * are allocated once and never moved.
 *
 * <p>Segmented rather than one growable buffer for a specific reason: capture
 * appends from three event loops while the recorder thread, the publisher
 * thread and REST readers hold slices into the same storage. A growable buffer
 * reallocates and copies when it runs out of room, which would silently
 * invalidate every outstanding slice. Segments never move, so a slice stays
 * valid for as long as anyone holds it.
 *
 * <p>A message is never split across segments -- if it doesn't fit in what's
 * left of the current one, the remainder is abandoned and the message starts a
 * new segment. That wastes a few bytes and buys two things: any single message
 * can be sliced without stitching, and consecutive messages that <em>are</em>
 * adjacent in memory can be handed to a socket as one write, which is what
 * makes the stimulus path fast.
 *
 * <p>The first segment can be an adopted buffer of any size, so loading a binary
 * file is one read into one region with every message an offset into it -- no
 * copy, and the whole file contiguous for coalescing.
 */
public final class MessageArena implements AutoCloseable {

    /** 16 MiB. Large enough that segment changes are rare, small enough to not over-allocate. */
    public static final int DEFAULT_SEGMENT_BYTES = 1 << 24;

    private final int segmentBytes;
    private final List<ByteBuf> segments = new ArrayList<>();
    /** Global offset at which each segment starts. */
    private final List<Integer> starts = new ArrayList<>();
    private int totalWritten;
    private boolean closed;

    public MessageArena(int initialCapacity) {
        this(initialCapacity, DEFAULT_SEGMENT_BYTES);
    }

    public MessageArena(int initialCapacity, int segmentBytes) {
        this.segmentBytes = Math.max(segmentBytes, 64 * 1024);
        addSegment(Math.max(initialCapacity, 1024));
    }

    private MessageArena(ByteBuf adopted, int segmentBytes) {
        this.segmentBytes = segmentBytes;
        segments.add(adopted);
        starts.add(0);
        totalWritten = adopted.writerIndex();
    }

    /** Takes ownership of an existing buffer as the first segment, without copying. */
    public static MessageArena adopting(ByteBuf buf) {
        return new MessageArena(buf, DEFAULT_SEGMENT_BYTES);
    }

    private void addSegment(int capacity) {
        ByteBuf segment = Unpooled.directBuffer(capacity, capacity);
        starts.add(totalWritten);
        segments.add(segment);
    }

    /** Total bytes stored, including any abandoned tail of a segment. */
    public int size() {
        return totalWritten;
    }

    // ---- append (single writer, or externally serialised) ----------------

    public int append(byte[] src) {
        int at = reserve(src.length);
        ByteBuf segment = segments.get(segments.size() - 1);
        segment.writeBytes(src);
        totalWritten += src.length;
        return at;
    }

    public int append(ByteBuf src, int offset, int length) {
        int at = reserve(length);
        ByteBuf segment = segments.get(segments.size() - 1);
        segment.writeBytes(src, offset, length);
        totalWritten += length;
        return at;
    }

    /**
     * Makes room for {@code length} contiguous bytes and returns the global
     * offset they will occupy, starting a new segment if the current one cannot
     * hold them whole.
     */
    private int reserve(int length) {
        ByteBuf current = segments.get(segments.size() - 1);
        if (current.writableBytes() < length) {
            // Abandon the tail so the message stays contiguous.
            totalWritten += current.writableBytes();
            addSegment(Math.max(segmentBytes, length));
            current = segments.get(segments.size() - 1);
        }
        return starts.get(segments.size() - 1) + current.writerIndex();
    }

    // ---- read ------------------------------------------------------------

    private int segmentIndexOf(int offset) {
        int low = 0;
        int high = segments.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (starts.get(mid) <= offset) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /** Read/write view of a range that must lie inside one segment. */
    public ByteBuf slice(int offset, int length) {
        int index = segmentIndexOf(offset);
        return segments.get(index).slice(offset - starts.get(index), length);
    }

    /**
     * A slice that holds a reference on its segment. Hand this to a socket
     * write: Netty releases it on completion, which is exactly what keeps the
     * segment alive for as long as an in-flight write points into it.
     */
    public ByteBuf retainedSlice(int offset, int length) {
        int index = segmentIndexOf(offset);
        return segments.get(index).retainedSlice(offset - starts.get(index), length);
    }

    public void setBytes(int offset, byte[] src) {
        int index = segmentIndexOf(offset);
        segments.get(index).setBytes(offset - starts.get(index), src);
    }

    public byte[] copyOut(int offset, int length) {
        byte[] out = new byte[length];
        int index = segmentIndexOf(offset);
        segments.get(index).getBytes(offset - starts.get(index), out);
        return out;
    }

    public void getBytes(int offset, byte[] dst, int dstIndex, int length) {
        int index = segmentIndexOf(offset);
        segments.get(index).getBytes(offset - starts.get(index), dst, dstIndex, length);
    }

    public void reset() {
        for (int i = segments.size() - 1; i > 0; i--) {
            segments.remove(i).release();
            starts.remove(i);
        }
        segments.get(0).clear();
        totalWritten = 0;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (ByteBuf segment : segments) {
            segment.release();
        }
        segments.clear();
        starts.clear();
        totalWritten = 0;
    }
}
