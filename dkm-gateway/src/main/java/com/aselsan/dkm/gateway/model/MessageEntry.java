package com.aselsan.dkm.gateway.model;

/**
 * One message in a working set, as a reference into a {@link MessageArena}
 * rather than a copy of its bytes.
 *
 * <p>Header fields are hoisted out into plain longs because the replay pacer
 * and the list UI touch them constantly and neither should have to re-read the
 * wire on every access. The bytes in the arena stay authoritative: an edit
 * re-encodes into them, so what gets sent and what gets saved are the same
 * bytes the operator was looking at.
 */
public final class MessageEntry {

    public final long id;

    /** Peer link this message belongs to, resolved from sender/receiver (see SchemaModel.resolvePeer). */
    public long moduleId;
    public long msgId;
    /** Header timestamp in milliseconds. The shared replay clock is derived from this (FR-13). */
    public long timestamp;
    public int length;

    /** Offset into the owning arena, or -1 when the bytes live in {@link #overlay}. */
    public int offset;
    /** Set for a message created or re-encoded outside the arena; folded back in on the next compaction. */
    public byte[] overlay;

    /** Qualified schema name, or null when this msg_id is not in the schema. */
    public String typeName;
    /** Non-null when the message cannot be trusted: unknown type, wrong length, decode failure (NFR-5). */
    public String problem;

    public Origin origin;

    /**
     * Set by the pacer once the bytes have gone out; sent messages become
     * read-only history (FR-8).
     *
     * <p>Deliberately not volatile. At the stimulus rates this targets, a
     * volatile store per message is real throughput, and it buys nothing:
     * the pacer publishes its batch counter with a release afterwards, and
     * every reader loads that counter first, so these writes are visible
     * through it. Anything reading these fields without going through
     * {@code PlaybackEngine.sentCount()} or the session lock may see a stale
     * value for a few microseconds.
     */
    public boolean sent;

    /**
     * When this message crossed the wire, as epoch milliseconds: set by the
     * pacer for stimulus, and at append time for capture. One field for both
     * directions is what lets the session trace merge them into a single
     * chronological view (FR-32).
     */
    public long wallClock;

    public MessageEntry(long id) {
        this.id = id;
        this.offset = -1;
    }

    public boolean isEditable() {
        return !sent;
    }

}
