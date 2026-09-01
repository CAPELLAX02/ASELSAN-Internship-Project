package com.aselsan.dkm.gateway.events;

import com.aselsan.dkm.gateway.ws.EventSocket;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Single fan-out point for everything the UI is told out-of-band: link state,
 * playback transitions, decoded output batches, errors, log lines.
 *
 * <p>Each event is serialised exactly once and the resulting string goes to
 * every connection, rather than every connection re-serialising the same object.
 *
 * <p>Connections come from {@link OpenConnections}. The {@code
 * WebSocketConnection} an endpoint injects is a CDI proxy bound to that
 * connection's context; publishing happens from link event loops, the replay
 * pacer and the capture thread, none of which have that context, so holding on
 * to the proxy would fail on every publish.
 *
 * <p>Delivery is fire-and-forget and bounded: a browser that cannot keep up has
 * events dropped rather than queued, because every publisher here is on a path
 * that must not stall.
 */
@ApplicationScoped
public class EventHub {

    private static final Logger LOG = Logger.getLogger(EventHub.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int LOG_HISTORY = 2000;
    /** Events in flight to one browser before the rest are dropped. */
    private static final int MAX_IN_FLIGHT = 512;

    @Inject
    ObjectMapper mapper;

    @Inject
    OpenConnections connections;

    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    /** Bounded ring of recent log lines so a reconnecting UI can backfill. */
    private final ArrayList<LogEntry> history = new ArrayList<>(LOG_HISTORY);

    public void forget(String connectionId) {
        inFlight.remove(connectionId);
    }

    public int subscriberCount() {
        int total = 0;
        for (WebSocketConnection connection : connections.findByEndpointId(EventSocket.ID)) {
            if (connection.isOpen()) {
                total++;
            }
        }
        return total;
    }

    public long droppedEvents() {
        return dropped.get();
    }

    public ObjectNode newEvent(String type) {
        ObjectNode event = NODES.objectNode();
        event.put("type", type);
        event.put("t", System.currentTimeMillis());
        return event;
    }

    public void publish(ObjectNode event) {
        Iterable<WebSocketConnection> targets = connections.findByEndpointId(EventSocket.ID);
        if (!targets.iterator().hasNext()) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            LOG.error("failed to serialise event", e);
            return;
        }
        for (WebSocketConnection connection : targets) {
            if (!connection.isOpen()) {
                inFlight.remove(connection.id());
                continue;
            }
            AtomicInteger pending = inFlight.computeIfAbsent(connection.id(), id -> new AtomicInteger());
            if (pending.get() >= MAX_IN_FLIGHT) {
                dropped.incrementAndGet();
                continue;
            }
            pending.incrementAndGet();
            connection.sendText(json).subscribe().with(
                    ignored -> pending.decrementAndGet(),
                    failure -> {
                        pending.decrementAndGet();
                        LOG.debugf("event to %s failed: %s", connection.id(), failure.toString());
                    });
        }
    }

    public void publish(String type, Consumer<ObjectNode> fill) {
        ObjectNode event = newEvent(type);
        fill.accept(event.putObject("data"));
        publish(event);
    }

    // ---- session log ----------------------------------------------------

    public void info(String source, String message) {
        log("INFO", source, message, null, Map.of());
    }

    public void warn(String source, String message) {
        log("WARN", source, message, null, Map.of());
    }

    public void error(String source, String message) {
        log("ERROR", source, message, null, Map.of());
    }

    /** Same, with a translation key and its parameters for the console. */
    public void info(String source, String message, String key, Map<String, Object> params) {
        log("INFO", source, message, key, params);
    }

    public void warn(String source, String message, String key, Map<String, Object> params) {
        log("WARN", source, message, key, params);
    }

    public void error(String source, String message, String key, Map<String, Object> params) {
        log("ERROR", source, message, key, params);
    }

    public void log(String level, String source, String message) {
        log(level, source, message, null, Map.of());
    }

    /**
     * Logs a line that the console can render in its own language.
     *
     * <p>The English text is always sent: it is what goes to the server log and
     * what a client with no translation for {@code key} falls back to, so a new
     * message is never invisible just because nobody has translated it yet. The
     * key and its parameters ride alongside for the clients that can do better.
     */
    public void log(String level, String source, String message, String key, Map<String, Object> params) {
        LogEntry entry = new LogEntry(sequence.incrementAndGet(), System.currentTimeMillis(),
                level, source, message, key, params == null ? Map.of() : params);
        synchronized (history) {
            if (history.size() == LOG_HISTORY) {
                history.remove(0);
            }
            history.add(entry);
        }
        switch (level) {
            case "ERROR" -> LOG.errorf("[%s] %s", source, message);
            case "WARN" -> LOG.warnf("[%s] %s", source, message);
            default -> LOG.infof("[%s] %s", source, message);
        }
        ObjectNode event = newEvent("log");
        ObjectNode data = event.putObject("data");
        data.put("seq", entry.seq());
        data.put("t", entry.wallClock());
        data.put("level", entry.level());
        data.put("source", entry.source());
        data.put("message", entry.message());
        if (entry.key() != null) {
            data.put("key", entry.key());
            ObjectNode bag = data.putObject("params");
            entry.params().forEach((name, value) -> {
                if (value instanceof Number number) {
                    bag.put(name, number.doubleValue());
                } else {
                    bag.put(name, String.valueOf(value));
                }
            });
        }
        publish(event);
    }

    public List<LogEntry> recentLog(int limit) {
        synchronized (history) {
            int from = Math.max(0, history.size() - limit);
            return List.copyOf(history.subList(from, history.size()));
        }
    }
}
