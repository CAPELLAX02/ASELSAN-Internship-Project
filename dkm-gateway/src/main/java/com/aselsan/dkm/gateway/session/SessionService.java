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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /**
     * The bytes exactly as they were loaded, so the operator can put an edited
     * set back the way it came.
     *
     * <p>A file rather than a second buffer in memory: the working set is
     * already held off-heap in one arena, and keeping a second copy of it
     * resident would double the cost of the very thing this tool is built to
     * handle at size. Edits write into the arena in place, so the original
     * cannot be recovered from it -- something has to hold it, and a temporary
     * file is the cheapest place.
     */
    private Path original;

    /** Undo and redo for everything the operator does to the set (FR-8, FR-9). */
    private final EditHistory history = new EditHistory();

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
            rememberOriginal(buf, name);
            history.clear();
            MessageSet.ParseResult result = messages.adopt(buf, name);
            events.info("session", "loaded " + result.messages() + " message(s) from " + name
                    + " (" + result.bytesConsumed() + " bytes)",
                    "log.session.loaded",
                    Map.of("count", result.messages(), "name", name, "bytes", result.bytesConsumed()));
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

    /** Whether there is a loaded file to go back to. */
    public boolean canRevert() {
        return original != null && Files.isReadable(original);
    }

    /**
     * Puts the set back exactly as the file was loaded, dropping every edit,
     * insertion, deletion and reorder since.
     */
    public MessageSet.ParseResult revert() throws IOException {
        Path snapshot = original;
        if (snapshot == null || !Files.isReadable(snapshot)) {
            throw new IllegalStateException("nothing to revert to -- no file has been loaded this session");
        }
        String name = read(() -> messages.sourceName());
        try (InputStream in = Files.newInputStream(snapshot)) {
            MessageSet.ParseResult result = load(in, name, Files.size(snapshot));
            events.info("session", "reverted to " + name + " as loaded; every edit since was dropped",
                    "log.session.reverted", Map.of("name", name));
            return result;
        }
    }

    private void rememberOriginal(ByteBuf buf, String name) {
        try {
            if (original == null) {
                original = Files.createTempFile("dkm-session-", ".bin");
                original.toFile().deleteOnExit();
            }
            try (OutputStream out = Files.newOutputStream(original)) {
                buf.getBytes(0, out, buf.writerIndex());
            }
        } catch (IOException e) {
            // Not fatal: the set still loads, only "revert" becomes unavailable,
            // and the console reads that from canRevert() rather than guessing.
            original = null;
            events.warn("session", "could not keep a copy of " + name + " for revert: " + e.getMessage());
        }
    }

    public void clear() {
        lock.lock();
        try {
            messages.clear();
            history.clear();
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

            int at = messages.indexOf(id);
            List<EditHistory.Entry> before = capture(at, at + 1);
            messages.replace(entry, updated);
            history.record(new EditHistory.Step("edit " + entry.typeName, at, before, capture(at, at + 1)));
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
            history.record(new EditHistory.Step("insert " + type.qualifiedName, at,
                    List.of(), capture(at, at + 1)));
            events.info("session", "inserted " + type.qualifiedName + " at position " + at
                    + " with timestamp " + timestamp + " ms (" + (offsetMillis >= 0 ? "+" : "")
                    + offsetMillis + " ms relative to its predecessor)",
                    "log.session.inserted", Map.of("type", type.qualifiedName, "index", at + 1,
                            "timestamp", timestamp, "offset", offsetMillis));
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
            int at = messages.indexOf(id);
            List<EditHistory.Entry> before = capture(at, at + 1);
            boolean removed = messages.remove(id);
            if (removed) {
                history.record(new EditHistory.Step("delete " + entry.typeName, at, before, List.of()));
                publishChanged(entry, "deleted");
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Moves a pending message to another position (FR-9).
     *
     * <p>A sent message cannot be moved for the same reason it cannot be edited:
     * it is history, and history that reorders itself is not history.
     *
     * @return the index it ended up at
     */
    /**
     * Moves a pending message to another position (FR-9).
     *
     * <p>What travels with the message is its gap from the message before it,
     * not its absolute timestamp. A recording is a sequence of intervals: a
     * detection two milliseconds after its beam is two milliseconds after its
     * beam wherever that pair ends up. Carrying the absolute timestamp instead
     * would make a moved message adopt whatever moment its new slot happened to
     * hold, which is a different recording, not a reordered one.
     *
     * <p>Absolutes are then re-derived as a running sum. Because addition does
     * not care about order, the totals past the moved range come out unchanged
     * on their own -- so only the messages between the old and new positions are
     * rewritten, however long the list is.
     *
     * @return the index it ended up at
     */
    public int move(long id, int toIndex) {
        lock.lock();
        try {
            MessageEntry entry = messages.byId(id);
            if (entry == null) {
                return -1;
            }
            if (entry.sent) {
                throw new IllegalStateException("message " + id + " has already been sent and cannot be moved");
            }
            List<MessageEntry> list = messages.entries();
            int from = messages.indexOf(id);
            int to = Math.min(Math.max(toIndex, 0), list.size() - 1);
            if (from < 0) {
                return -1;
            }
            if (from == to) {
                return to;
            }

            int low = Math.min(from, to);
            int high = Math.max(from, to);
            List<EditHistory.Entry> before = capture(low, high + 1);

            // Each message's own gap, captured before anything moves.
            long[] gaps = new long[list.size()];
            for (int i = 0; i < list.size(); i++) {
                gaps[i] = i == 0 ? list.get(i).timestamp
                        : Math.max(0, list.get(i).timestamp - list.get(i - 1).timestamp);
            }
            long[] carried = new long[high - low + 1];
            for (int i = low; i <= high; i++) {
                carried[i - low] = gaps[i];
            }
            // The gaps travel with their messages through the same reorder.
            long moved = carried[from - low];
            long[] reordered = new long[carried.length];
            int write = 0;
            for (int i = 0; i < carried.length; i++) {
                if (i + low == from) continue;
                if (write + low == to) reordered[write++] = moved;
                reordered[write++] = carried[i];
            }
            if (write < reordered.length) reordered[write] = moved;

            int at = messages.move(id, to);
            if (at < 0) {
                return -1;
            }

            long running = low == 0 ? 0 : list.get(low - 1).timestamp;
            for (int i = low; i <= high; i++) {
                long wanted = low == 0 && i == low ? reordered[0] : running + reordered[i - low];
                running = wanted;
                MessageEntry occupant = list.get(i);
                if (occupant.timestamp != wanted) {
                    rewriteTimestamp(occupant, wanted);
                }
            }
            history.record(new EditHistory.Step("move " + entry.typeName, low,
                    before, capture(low, high + 1)));
            publishChanged(entry, "moved");
            return at;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The position a message with this timestamp belongs at, so the list stays
     * in ascending time. Ties go after the equal ones, which keeps a burst that
     * shares a millisecond in the order it was built.
     */
    private int sortedIndexFor(long timestamp, long excludeId) {
        List<MessageEntry> list = messages.entries();
        int at = 0;
        for (MessageEntry candidate : list) {
            if (candidate.id == excludeId) {
                continue;
            }
            if (candidate.timestamp > timestamp) {
                break;
            }
            at++;
        }
        return at;
    }

    // ---- undo / redo -----------------------------------------------------

    /** The messages occupying [from, to) right now, bytes and all. */
    private List<EditHistory.Entry> capture(int from, int to) {
        List<MessageEntry> list = messages.entries();
        int lo = Math.min(Math.max(from, 0), list.size());
        int hi = Math.min(Math.max(to, lo), list.size());
        List<EditHistory.Entry> captured = new ArrayList<>(hi - lo);
        for (int i = lo; i < hi; i++) {
            MessageEntry entry = list.get(i);
            captured.add(new EditHistory.Entry(entry.id, messages.bytesOf(entry)));
        }
        return captured;
    }

    public boolean canUndo() {
        return history.canUndo();
    }

    public boolean canRedo() {
        return history.canRedo();
    }

    public String undoLabel() {
        return history.undoLabel();
    }

    public String redoLabel() {
        return history.redoLabel();
    }

    /** Puts one step back. Returns what it was, or null if there was nothing. */
    public String undo() {
        lock.lock();
        try {
            EditHistory.Step step = history.popUndo();
            if (step == null) {
                return null;
            }
            apply(step.at(), step.after().size(), step.before());
            publishReloaded();
            events.info("session", "undid: " + step.label(),
                    "log.session.undone", Map.of("what", step.label()));
            return step.label();
        } finally {
            lock.unlock();
        }
    }

    public String redo() {
        lock.lock();
        try {
            EditHistory.Step step = history.popRedo();
            if (step == null) {
                return null;
            }
            apply(step.at(), step.before().size(), step.after());
            publishReloaded();
            events.info("session", "redid: " + step.label(),
                    "log.session.redone", Map.of("what", step.label()));
            return step.label();
        } finally {
            lock.unlock();
        }
    }

    private void apply(int at, int removeCount, List<EditHistory.Entry> entries) {
        long[] ids = new long[entries.size()];
        List<byte[]> bodies = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            ids[i] = entries.get(i).id();
            bodies.add(entries.get(i).bytes());
        }
        messages.splice(at, removeCount, ids, bodies, Origin.NEW);
    }

    /** Writes a new timestamp into a message's header, leaving every other byte alone. */
    private void rewriteTimestamp(MessageEntry entry, long timestamp) {
        byte[] bytes = messages.bytesOf(entry);
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        MessageCodec codec = schemaService.codec();
        codec.writeHeader(buf, 0, codec.senderId(buf, 0), codec.receiverId(buf, 0),
                codec.msgId(buf, 0), Math.max(0, timestamp), codec.msgLength(buf, 0));
        messages.replace(entry, bytes);
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
            long wanted = Math.max(0, timestamp);
            int was = messages.indexOf(id);
            int goes = sortedIndexFor(wanted, entry.id);
            int low = Math.min(was, goes);
            int high = Math.max(was, goes);
            List<EditHistory.Entry> before = capture(low, high + 1);
            rewriteTimestamp(entry, wanted);
            // A message's place in the list is its place in time. Re-timing it
            // without moving it would leave a list that no longer reads in the
            // order it will be sent, which is the one thing the list is for.
            messages.move(entry.id, goes);
            history.record(new EditHistory.Step("retime " + entry.typeName, low,
                    before, capture(low, high + 1)));
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
        resetSentMarkers(0);
    }

    /**
     * Clears the sent markers, then marks everything before {@code fromId} as
     * already sent so the next run begins there (0 means the whole set).
     *
     * <p>Expressed through the sent markers rather than as a separate "start
     * index" because that is where the engine already looks: FR-14 rebuilds the
     * plan from whatever is unsent on every start and resume, so starting part
     * way through needs no new concept and behaves correctly if the operator
     * then pauses, edits and resumes.
     */
    public void resetSentMarkers(long fromId) {
        lock.lock();
        try {
            boolean before = fromId != 0;
            for (MessageEntry entry : messages.entries()) {
                if (before && entry.id == fromId) {
                    before = false;
                }
                entry.sent = before;
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
