package com.aselsan.dkm.gateway.session;

import com.aselsan.dkm.gateway.events.EventHub;
import com.aselsan.dkm.gateway.model.MessageEntry;
import com.aselsan.dkm.gateway.model.MessageSet;
import com.aselsan.dkm.gateway.model.Origin;
import com.aselsan.dkm.gateway.schema.CodecException;
import com.aselsan.dkm.gateway.schema.CompiledMessage;
import com.aselsan.dkm.gateway.schema.MessageCodec;
import com.aselsan.dkm.gateway.schema.SchemaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The stimulus working set: the messages that will be (or have been) sent to
 * the DKM, loaded from a binary, edited in the UI, and saved back out.
 *
 * <p>Every mutation runs under one lock, and the playback pacer takes the same
 * lock only to build its plan -- never while sending. That keeps editing simple
 * and correct without putting a lock on the hot path.
 */
@ApplicationScoped
public class SessionService {

    @Inject
    SchemaService schemaService;

    @Inject
    EventHub events;

    private final ReentrantLock lock = new ReentrantLock();
    private MessageSet messages;

    @PostConstruct
    void init() {
        messages = new MessageSet(schemaService.model(), schemaService.codec(), 64 * 1024);
    }

    void onStop(@Observes ShutdownEvent event) {
        lock.lock();
        try {
            messages.close();
        } finally {
            lock.unlock();
        }
    }

    public <T> T read(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public ReentrantLock lock() {
        return lock;
    }

    /** Only safe to touch while holding {@link #lock()}. */
    public MessageSet messages() {
        return messages;
    }

    // ---- load / save ----------------------------------------------------

    /** FR-6: load an existing input binary and split it purely by msg_length. */
    public MessageSet.ParseResult load(InputStream in, String name, long expectedSize) throws IOException {
        ByteBuf buf = Unpooled.directBuffer((int) Math.max(expectedSize, 64 * 1024));
        byte[] chunk = new byte[256 * 1024];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buf.ensureWritable(read);
            buf.writeBytes(chunk, 0, read);
        }
        lock.lock();
        try {
            MessageSet.ParseResult result = messages.adopt(buf, name);
            events.info("session", "loaded " + result.messages() + " message(s) from " + name
                    + " (" + result.bytesConsumed() + " bytes)");
            for (String note : result.notes()) {
                events.warn("session", name + ": " + note);
            }
            publishReloaded();
            return result;
        } finally {
            lock.unlock();
        }
    }

    public MessageSet.ParseResult loadFile(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return load(in, path.getFileName().toString(), Files.size(path));
        }
    }

    /** FR-10: write the (possibly edited) set back out in the original format. */
    public long save(OutputStream out) throws IOException {
        lock.lock();
        try {
            return messages.writeTo(out);
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            messages.clear();
            publishReloaded();
        } finally {
            lock.unlock();
        }
    }

    // ---- editing --------------------------------------------------------

    /** FR-8: apply field edits to a not-yet-sent message. */
    public MessageEntry edit(long id, JsonNode patch) {
        lock.lock();
        try {
            MessageEntry entry = messages.byId(id);
            if (entry == null) {
                throw new IllegalArgumentException("no message with id " + id);
            }
            if (entry.sent) {
                throw new IllegalStateException("message " + id
                        + " has already been sent this run and is history now -- it cannot be edited (FR-8)");
            }
            CompiledMessage type = requireType(entry);
            MessageCodec codec = schemaService.codec();

            // Start from the message's current bytes so anything the schema does
            // not describe survives the edit untouched (NFR-4).
            byte[] updated = messages.bytesOf(entry);
            ByteBuf target = Unpooled.wrappedBuffer(updated);
            codec.encode(type, patch, target, 0);

            // msg_length stays authoritative and stays consistent with reality.
            codec.writeHeader(target, 0,
                    codec.senderId(target, 0), codec.receiverId(target, 0),
                    type.msgId, codec.timestamp(target, 0), updated.length);

            messages.replace(entry, updated);
            publishChanged(entry, "edited");
            return entry;
        } finally {
            lock.unlock();
        }
    }

    /** FR-9: insert a brand-new message at a chosen position with an explicit timing offset. */
    public MessageEntry insert(String typeName, int index, long offsetMillis, JsonNode payload) {
        return insert(typeName, index, offsetMillis, payload, Origin.NEW);
    }

    public MessageEntry insert(String typeName, int index, long offsetMillis, JsonNode payload, Origin origin) {
        lock.lock();
        try {
            CompiledMessage type = schemaService.model().message(typeName);
            if (type == null) {
                throw new IllegalArgumentException("unknown message type " + typeName);
            }
            int at = Math.min(Math.max(index, 0), messages.size());
            long baseTimestamp = baseTimestampFor(at);
            long timestamp = Math.max(0, baseTimestamp + offsetMillis);

            byte[] bytes = build(type, timestamp, payload);
            MessageEntry entry = messages.insertAt(at, bytes, origin);
            events.info("session", "inserted " + type.qualifiedName + " at position " + at
                    + " with timestamp " + timestamp + " ms (" + (offsetMillis >= 0 ? "+" : "")
                    + offsetMillis + " ms relative to its predecessor)");
            publishChanged(entry, "inserted");
            return entry;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Encodes a message from scratch: zeroed bytes, then the header the wire
     * expects, then the caller's payload over the top.
     */
    public byte[] build(CompiledMessage type, long timestamp, JsonNode payload) {
        MessageCodec codec = schemaService.codec();
        byte[] bytes = new byte[type.size];
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        codec.writeHeader(buf, 0, type.module.id(), schemaService.model().dkmModule().id(),
                type.msgId, timestamp, type.size);
        if (payload != null && !payload.isNull()) {
            ObjectNode wrapper = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            wrapper.set("payload", payload);
            codec.encode(type, wrapper, buf, 0);
        }
        return bytes;
    }

    private long baseTimestampFor(int index) {
        if (messages.size() == 0) {
            return 0;
        }
        if (index == 0) {
            return messages.entries().get(0).timestamp;
        }
        return messages.entries().get(index - 1).timestamp;
    }

    public boolean delete(long id) {
        lock.lock();
        try {
            MessageEntry entry = messages.byId(id);
            if (entry == null) {
                return false;
            }
            if (entry.sent) {
                throw new IllegalStateException("message " + id + " has already been sent and cannot be removed");
            }
            boolean removed = messages.remove(id);
            if (removed) {
                publishChanged(entry, "deleted");
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    /** Re-timestamps a pending message; the shared replay clock re-derives from this on the next start (FR-14). */
    public MessageEntry retime(long id, long timestamp) {
        lock.lock();
        try {
            MessageEntry entry = messages.byId(id);
            if (entry == null) {
                throw new IllegalArgumentException("no message with id " + id);
            }
            if (entry.sent) {
                throw new IllegalStateException("message " + id + " has already been sent");
            }
            byte[] bytes = messages.bytesOf(entry);
            ByteBuf buf = Unpooled.wrappedBuffer(bytes);
            MessageCodec codec = schemaService.codec();
            codec.writeHeader(buf, 0, codec.senderId(buf, 0), codec.receiverId(buf, 0),
                    codec.msgId(buf, 0), Math.max(0, timestamp), codec.msgLength(buf, 0));
            messages.replace(entry, bytes);
            publishChanged(entry, "retimed");
            return entry;
        } finally {
            lock.unlock();
        }
    }

    public CompiledMessage requireType(MessageEntry entry) {
        CompiledMessage type = entry.typeName == null ? null : schemaService.model().message(entry.typeName);
        if (type == null) {
            throw new CodecException("message " + entry.id + " has no usable schema type"
                    + (entry.problem != null ? " (" + entry.problem + ")" : ""));
        }
        return type;
    }

    /** Clears the sent markers so the whole set is editable and replayable again (FR-11 "stop"). */
    public void resetSentMarkers() {
        lock.lock();
        try {
            for (MessageEntry entry : messages.entries()) {
                entry.sent = false;
                entry.wallClock = 0;
            }
            publishReloaded();
        } finally {
            lock.unlock();
        }
    }

    public List<String> problems() {
        lock.lock();
        try {
            return messages.entries().stream()
                    .filter(e -> e.problem != null)
                    .map(e -> "#" + e.id + " " + e.problem)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    private void publishReloaded() {
        events.publish("session", data -> {
            data.put("action", "reloaded");
            data.put("source", messages.sourceName());
            data.put("count", messages.size());
        });
    }

    private void publishChanged(MessageEntry entry, String action) {
        events.publish("session", data -> {
            data.put("action", action);
            data.put("id", entry.id);
            data.put("type", entry.typeName);
            data.put("count", messages.size());
        });
    }
}
