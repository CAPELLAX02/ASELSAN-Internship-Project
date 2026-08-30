package com.aselsan.dkm.gateway.capture;

import com.aselsan.dkm.gateway.events.EventHub;
import com.aselsan.dkm.gateway.model.MessageEntry;
import com.aselsan.dkm.gateway.model.MessageSet;
import com.aselsan.dkm.gateway.model.Origin;
import com.aselsan.dkm.gateway.net.InboundSink;
import com.aselsan.dkm.gateway.net.Link;
import com.aselsan.dkm.gateway.schema.CompiledMessage;
import com.aselsan.dkm.gateway.schema.SchemaService;
import com.aselsan.dkm.gateway.viz.VizPublisher;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBuf;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Captures everything the DKM sends back (FR-19..FR-21).
 *
 * <p>The receive path does exactly three things, all of them bounded and none
 * of them blocking: copy the bytes into the capture arena, push a visualization
 * sample into a ring, and return to reading the socket. Decoding to JSON,
 * pushing to the UI and writing to disk all happen on a separate thread that the
 * socket never waits for -- the same split mock_r itself uses between its
 * receive threads and its processing threads, and for the same reason.
 *
 * <p>Bytes are stored exactly as they arrived, so a captured message that the
 * schema cannot explain is still kept, still saved byte-exact, and still
 * reported -- with the reason attached rather than a silent reinterpretation.
 */
@ApplicationScoped
public class CaptureService implements InboundSink {

    private static final Logger LOG = Logger.getLogger(CaptureService.class);

    @Inject
    SchemaService schemaService;

    @Inject
    VizPublisher viz;

    @Inject
    EventHub events;

    @Inject
    CaptureConfig config;

    private final ReentrantLock lock = new ReentrantLock();
    private MessageSet captured;
    private ScheduledExecutorService worker;

    private int publishCursor;
    private int recordCursor;
    private OutputStream recordStream;
    private boolean capacityWarned;
    private long overflowed;

    void onStart(@Observes StartupEvent event) {
        captured = new MessageSet(schemaService.model(), schemaService.codec(), 1 << 20);
        config.recordPath().ifPresent(this::openRecording);
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dkm-capture");
            thread.setDaemon(true);
            return thread;
        });
        long interval = config.publishIntervalMillis();
        worker.scheduleAtFixedRate(this::drain, interval, interval, TimeUnit.MILLISECONDS);
    }

    void onStop(@Observes ShutdownEvent event) {
        if (worker != null) {
            worker.shutdownNow();
        }
        closeRecording();
        lock.lock();
        try {
            captured.close();
        } finally {
            lock.unlock();
        }
    }

    private void openRecording(String path) {
        try {
            Path target = Path.of(path);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            recordStream = new BufferedOutputStream(Files.newOutputStream(target,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                    1 << 20);
            events.info("capture", "mirroring DKM output to " + target.toAbsolutePath());
        } catch (IOException e) {
            events.error("capture", "could not open " + path + " for recording -- " + e.getMessage());
        }
    }

    private void closeRecording() {
        OutputStream stream = recordStream;
        recordStream = null;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                LOG.warn("failed to close the capture recording", e);
            }
        }
    }

    // ---- receive path (link event loop) ---------------------------------

    @Override
    public void onInbound(Link link, ByteBuf buf, int offset, int length) {
        // Sample for the picture first, straight out of the receive buffer while
        // it is still in cache, and before taking any lock.
        long msgId = schemaService.codec().msgId(buf, offset);
        long senderId = schemaService.codec().senderId(buf, offset);
        long receiverId = schemaService.codec().receiverId(buf, offset);
        var peer = schemaService.model().resolvePeer(senderId, receiverId);
        long moduleId = peer != null ? peer.id() : link.moduleId();
        viz.onInbound(link.index, moduleId, msgId, buf, offset);

        lock.lock();
        try {
            if (captured.size() >= config.maxMessages()) {
                overflowed++;
                if (!capacityWarned) {
                    capacityWarned = true;
                    events.error("capture", "capture buffer is full at " + config.maxMessages()
                            + " messages -- further output is NOT being kept. Save and clear the capture,"
                            + " or raise dkm.capture.max-messages.");
                }
                return;
            }
            captured.append(buf, offset, length, Origin.CAPTURE).wallClock = System.currentTimeMillis();
        } finally {
            lock.unlock();
        }
    }

    // ---- worker thread ---------------------------------------------------

    private void drain() {
        try {
            List<MessageEntry> fresh;
            int total;
            lock.lock();
            try {
                total = captured.size();
                int from = publishCursor;
                int to = Math.min(total, from + config.maxPublishedPerBatch());
                fresh = from < to ? new ArrayList<>(captured.entries().subList(from, to)) : List.of();
                publishCursor = to;
            } finally {
                lock.unlock();
            }

            record(total);

            if (!fresh.isEmpty()) {
                publish(fresh, total);
            }
        } catch (RuntimeException e) {
            LOG.error("capture drain failed", e);
        }
    }

    private void record(int total) {
        OutputStream stream = recordStream;
        if (stream == null || recordCursor >= total) {
            return;
        }
        List<MessageEntry> pending;
        lock.lock();
        try {
            pending = new ArrayList<>(captured.entries().subList(recordCursor, total));
            recordCursor = total;
        } finally {
            lock.unlock();
        }
        try {
            for (MessageEntry entry : pending) {
                // Safe outside the lock: arena segments never move once written.
                stream.write(captured.bytesOf(entry));
            }
            stream.flush();
        } catch (IOException e) {
            events.error("capture", "recording failed -- " + e.getMessage());
            closeRecording();
        }
    }

    private void publish(List<MessageEntry> fresh, int total) {
        ObjectNode event = events.newEvent("capture");
        ObjectNode data = event.putObject("data");
        data.put("total", total);
        data.put("overflowed", overflowed);
        ArrayNode array = data.putArray("messages");
        for (MessageEntry entry : fresh) {
            array.add(summary(entry));
        }
        events.publish(event);
    }

    // ---- queries ----------------------------------------------------------

    public <T> T read(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public MessageSet messages() {
        return captured;
    }

    public ReentrantLock lock() {
        return lock;
    }

    public int size() {
        lock.lock();
        try {
            return captured.size();
        } finally {
            lock.unlock();
        }
    }

    public long overflowed() {
        return overflowed;
    }

    public void clear() {
        lock.lock();
        try {
            captured.clear();
            publishCursor = 0;
            recordCursor = 0;
            overflowed = 0;
            capacityWarned = false;
        } finally {
            lock.unlock();
        }
        viz.resetScene();
        events.info("capture", "capture cleared");
        events.publish("capture", data -> {
            data.put("total", 0);
            data.put("cleared", true);
            data.putArray("messages");
        });
    }

    public ObjectNode summary(MessageEntry entry) {
        ObjectNode node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        node.put("id", entry.id);
        node.put("moduleId", entry.moduleId);
        node.put("msgId", entry.msgId);
        node.put("timestamp", entry.timestamp);
        node.put("length", entry.length);
        node.put("type", entry.typeName);
        node.put("problem", entry.problem);
        node.put("origin", entry.origin.name());
        CompiledMessage type = entry.typeName == null ? null : schemaService.model().message(entry.typeName);
        node.put("direction", type == null ? "FROM_DKM" : type.direction.name());
        return node;
    }
}
