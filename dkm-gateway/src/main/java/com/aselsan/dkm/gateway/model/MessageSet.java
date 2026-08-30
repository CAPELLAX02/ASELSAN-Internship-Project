package com.aselsan.dkm.gateway.model;

import com.aselsan.dkm.gateway.schema.CompiledMessage;
import com.aselsan.dkm.gateway.schema.MessageCodec;
import com.aselsan.dkm.gateway.schema.ModuleDef;
import com.aselsan.dkm.gateway.schema.SchemaModel;
import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An ordered set of messages plus the arena holding their bytes -- the working
 * set behind both the editable stimulus list and the read-only capture list.
 *
 * <p>Parsing splits purely on {@code msg_length} (FR-6): a message whose
 * {@code msg_id} this build doesn't know, or whose length disagrees with the
 * schema, is still framed correctly, still kept, and still saved back out
 * byte-exact -- it is just flagged unsendable with the reason (NFR-5). Silently
 * dropping or reinterpreting it would be the worst possible failure mode for a
 * tool whose whole job is to tell an engineer what is actually on the wire.
 */
public final class MessageSet implements AutoCloseable {

    private static final AtomicLong IDS = new AtomicLong();

    private final SchemaModel schema;
    private final MessageCodec codec;
    private final List<MessageEntry> entries = new ArrayList<>();
    private MessageArena arena;
    private boolean dirty;
    private String sourceName = "(empty)";

    public MessageSet(SchemaModel schema, MessageCodec codec, int initialCapacity) {
        this.schema = schema;
        this.codec = codec;
        this.arena = new MessageArena(initialCapacity);
    }

    public List<MessageEntry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public MessageArena arena() {
        return arena;
    }

    public String sourceName() {
        return sourceName;
    }

    public void sourceName(String name) {
        this.sourceName = name;
    }

    public long totalBytes() {
        long total = 0;
        for (MessageEntry e : entries) {
            total += e.length;
        }
        return total;
    }

    // ---- parsing --------------------------------------------------------

    /**
     * Splits {@code source} into messages, taking ownership of it as this set's
     * arena. Zero copies: entries reference the file bytes in place.
     */
    public ParseResult adopt(ByteBuf source, String name) {
        if (arena != null) {
            arena.close();
        }
        entries.clear();
        this.arena = MessageArena.adopting(source);
        this.sourceName = name;
        dirty = false;

        int headerSize = schema.headerSize();
        int total = source.writerIndex();
        int cursor = 0;
        int malformed = 0;
        List<String> notes = new ArrayList<>();

        while (cursor < total) {
            if (total - cursor < headerSize) {
                notes.add("trailing " + (total - cursor) + " byte(s) are too short to be a header -- ignored");
                break;
            }
            long declared = codec.msgLength(source, cursor);
            if (declared < headerSize || declared > total - cursor) {
                notes.add("message #" + entries.size() + " at byte " + cursor + " declares msg_length="
                        + Long.toUnsignedString(declared) + ", which is impossible here -- stopping the scan");
                malformed++;
                break;
            }
            int length = (int) declared;
            entries.add(describe(source, cursor, length, Origin.FILE));
            cursor += length;
        }
        return new ParseResult(entries.size(), cursor, malformed, notes);
    }

    /** Builds an entry for a message already present at {@code offset} in the arena. */
    public MessageEntry describe(ByteBuf buf, int offset, int length, Origin origin) {
        MessageEntry entry = new MessageEntry(IDS.incrementAndGet());
        entry.offset = offset;
        entry.length = length;
        entry.origin = origin;
        entry.msgId = codec.msgId(buf, offset);
        entry.timestamp = codec.timestamp(buf, offset);

        long senderId = codec.senderId(buf, offset);
        long receiverId = codec.receiverId(buf, offset);
        ModuleDef peer = schema.resolvePeer(senderId, receiverId);
        if (peer == null) {
            entry.moduleId = senderId;
            entry.problem = "sender_id=" + senderId + " / receiver_id=" + receiverId
                    + " does not name a known peer link";
            return entry;
        }
        entry.moduleId = peer.id();

        CompiledMessage type = schema.message(peer.id(), entry.msgId);
        if (type == null) {
            entry.problem = "msg_id " + entry.msgId + " is not defined on the " + peer.name()
                    + " link by schema " + schema.version();
            return entry;
        }
        entry.typeName = type.qualifiedName;
        if (length != type.size) {
            entry.problem = type.qualifiedName + " is " + type.size + " bytes in schema "
                    + schema.version() + " but this message is " + length
                    + " -- the data predates the current interface (or the schema does)";
        }
        return entry;
    }

    // ---- mutation -------------------------------------------------------

    /** Appends a fully-formed message; used by capture and by "new message" (FR-9). */
    public MessageEntry append(byte[] bytes, Origin origin) {
        int offset = arena.append(bytes);
        MessageEntry entry = describe(arena.slice(offset, bytes.length), 0, bytes.length, origin);
        entry.offset = offset;
        entries.add(entry);
        return entry;
    }

    /** Appends bytes read straight out of a receive buffer, without an intermediate array. */
    public MessageEntry append(ByteBuf src, int offset, int length, Origin origin) {
        int at = arena.append(src, offset, length);
        MessageEntry entry = describe(arena.slice(at, length), 0, length, origin);
        entry.offset = at;
        entries.add(entry);
        return entry;
    }

    public MessageEntry insertAt(int index, byte[] bytes, Origin origin) {
        MessageEntry entry = new MessageEntry(IDS.incrementAndGet());
        entry.overlay = bytes;
        entry.offset = -1;
        entry.length = bytes.length;
        entry.origin = origin;
        MessageEntry described = describe(io.netty.buffer.Unpooled.wrappedBuffer(bytes), 0, bytes.length, origin);
        entry.moduleId = described.moduleId;
        entry.msgId = described.msgId;
        entry.timestamp = described.timestamp;
        entry.typeName = described.typeName;
        entry.problem = described.problem;
        entries.add(Math.min(Math.max(index, 0), entries.size()), entry);
        dirty = true;
        return entry;
    }

    public boolean remove(long id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id == id) {
                entries.remove(i);
                dirty = true;
                return true;
            }
        }
        return false;
    }

    /**
     * Replaces one message's bytes.
     *
     * <p>Same length -- which is every message type in the current interface,
     * all of them fixed-size -- writes straight into the arena and keeps every
     * other offset valid. A length change (a genuinely variable-size type)
     * falls back to an overlay and marks the set for compaction.
     */
    public void replace(MessageEntry entry, byte[] bytes) {
        if (entry.overlay == null && bytes.length == entry.length) {
            arena.setBytes(entry.offset, bytes);
        } else {
            entry.overlay = bytes;
            entry.offset = -1;
            entry.length = bytes.length;
            dirty = true;
        }
        MessageEntry described = describe(io.netty.buffer.Unpooled.wrappedBuffer(bytes), 0, bytes.length, entry.origin);
        entry.moduleId = described.moduleId;
        entry.msgId = described.msgId;
        entry.timestamp = described.timestamp;
        entry.typeName = described.typeName;
        entry.problem = described.problem;
    }

    /** Folds overlays back into the arena so every entry is contiguous again. */
    public void compact() {
        if (!dirty) {
            return;
        }
        MessageArena rebuilt = new MessageArena((int) Math.max(totalBytes(), 1024));
        for (MessageEntry entry : entries) {
            int at;
            if (entry.overlay != null) {
                at = rebuilt.append(entry.overlay);
                entry.overlay = null;
            } else {
                at = rebuilt.append(arena.slice(entry.offset, entry.length), 0, entry.length);
            }
            entry.offset = at;
        }
        arena.close();
        arena = rebuilt;
        dirty = false;
    }

    // ---- access ---------------------------------------------------------

    public MessageEntry byId(long id) {
        for (MessageEntry e : entries) {
            if (e.id == id) {
                return e;
            }
        }
        return null;
    }

    public int indexOf(long id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id == id) {
                return i;
            }
        }
        return -1;
    }

    /** A read-only view of one message's bytes, valid until the next compaction. */
    public ByteBuf view(MessageEntry entry) {
        return entry.overlay != null
                ? io.netty.buffer.Unpooled.wrappedBuffer(entry.overlay)
                : arena.slice(entry.offset, entry.length);
    }

    public byte[] bytesOf(MessageEntry entry) {
        return entry.overlay != null ? entry.overlay.clone() : arena.copyOut(entry.offset, entry.length);
    }

    /** Writes the whole set back out in the original wire format (FR-10, FR-21). */
    public long writeTo(OutputStream out) throws IOException {
        byte[] scratch = new byte[64 * 1024];
        long written = 0;
        for (MessageEntry entry : entries) {
            if (entry.overlay != null) {
                out.write(entry.overlay);
            } else {
                int remaining = entry.length;
                int at = entry.offset;
                while (remaining > 0) {
                    int chunk = Math.min(remaining, scratch.length);
                    arena.getBytes(at, scratch, 0, chunk);
                    out.write(scratch, 0, chunk);
                    at += chunk;
                    remaining -= chunk;
                }
            }
            written += entry.length;
        }
        return written;
    }

    public void clear() {
        entries.clear();
        arena.reset();
        dirty = false;
        sourceName = "(empty)";
    }

    @Override
    public void close() {
        entries.clear();
        if (arena != null) {
            arena.close();
        }
    }

    /**
     * @param messages      how many messages were recognised
     * @param bytesConsumed how far the scan got
     * @param malformed     how many stopped the scan
     * @param notes         human-readable complaints to show the operator
     */
    public record ParseResult(int messages, int bytesConsumed, int malformed, List<String> notes) {
    }
}
