package com.aselsan.dkm.gateway.ws;

import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import io.vertx.core.buffer.Buffer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fan-out for binary visualization frames.
 *
 * <p>Live connections come from {@link OpenConnections}, which hands out real
 * connection objects usable from any thread. The {@code WebSocketConnection} an
 * endpoint injects is a CDI proxy bound to that connection's context, so
 * stashing it and calling it from the visualization thread throws -- and, being
 * a runtime failure on a background thread, throws somewhere nobody is looking.
 *
 * <p>Every viewer gets the same array; a frame is built once and never
 * re-serialised per connection. A viewer that falls behind has frames skipped
 * rather than queued -- for a live picture the newest frame is the only one
 * worth having, and unbounded queueing would turn a slow browser into a memory
 * leak on the server.
 */
@ApplicationScoped
public class VizHub {

    private static final Logger LOG = Logger.getLogger(VizHub.class);

    @Inject
    OpenConnections connections;

    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final LongAdder framesSent = new LongAdder();
    private final LongAdder framesSkipped = new LongAdder();

    public void forget(String connectionId) {
        inFlight.remove(connectionId);
    }

    public int subscriberCount() {
        int total = 0;
        for (WebSocketConnection connection : connections.findByEndpointId(VizSocket.ID)) {
            if (connection.isOpen()) {
                total++;
            }
        }
        return total;
    }

    public long framesSent() {
        return framesSent.sum();
    }

    public long framesSkipped() {
        return framesSkipped.sum();
    }

    /**
     * {@code frame} is wrapped, not copied, once per subscriber -- each wrapper
     * carries its own reference count, so one shared array can go to several
     * connections without any of them releasing it out from under another.
     */
    public void broadcast(byte[] frame, int maxInFlight) {
        for (WebSocketConnection connection : connections.findByEndpointId(VizSocket.ID)) {
            if (!connection.isOpen()) {
                inFlight.remove(connection.id());
                continue;
            }
            AtomicInteger pending = inFlight.computeIfAbsent(connection.id(), id -> new AtomicInteger());
            if (pending.get() >= maxInFlight) {
                framesSkipped.increment();
                continue;
            }
            pending.incrementAndGet();
            connection.sendBinary(Buffer.buffer(frame)).subscribe().with(
                    ignored -> {
                        pending.decrementAndGet();
                        framesSent.increment();
                    },
                    failure -> {
                        pending.decrementAndGet();
                        LOG.debugf("viz frame to %s failed: %s", connection.id(), failure.toString());
                    });
        }
    }
}
