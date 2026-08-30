package com.aselsan.dkm.gateway.ws;

import com.aselsan.dkm.gateway.events.EventHub;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Control-plane event stream: link state, playback transitions, decoded output
 * batches, log lines.
 *
 * <p>Separate from {@link VizSocket} on purpose. This channel carries JSON at
 * human rates -- a few messages a second -- while the visualization channel
 * carries binary at frame rates. Sharing one socket would let a burst of list
 * updates delay the picture, and would force the picture's payload through a
 * JSON encoder it has no use for.
 *
 * <p>Like {@link VizSocket} this endpoint holds no state: {@link EventHub}
 * pushes to live connections through {@code OpenConnections}, because the
 * injected connection here is a context-bound proxy and every publisher runs on
 * a thread with no such context.
 */
@WebSocket(path = "/ws/events", endpointId = EventSocket.ID)
public class EventSocket {

    private static final Logger LOG = Logger.getLogger(EventSocket.class);

    public static final String ID = "dkm-events";

    @Inject
    EventHub hub;

    @Inject
    WebSocketConnection connection;

    /**
     * Required by the extension: an endpoint must declare at least one lifecycle
     * or message method. There is genuinely nothing to do on open -- control events
     * are pushed, never requested -- so this only records that a viewer arrived.
     */
    @OnOpen
    public void onOpen() {
        LOG.debugf("event subscriber %s attached", connection.id());
    }

    @OnClose
    public void onClose() {
        hub.forget(connection.id());
    }
}
