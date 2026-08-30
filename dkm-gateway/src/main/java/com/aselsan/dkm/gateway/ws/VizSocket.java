package com.aselsan.dkm.gateway.ws;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Binary visualization stream.
 *
 * <p>Binary rather than JSON on purpose: a sample is 48 fixed bytes that the
 * browser reads straight into a typed array and hands to WebGL. JSON for the
 * same payload would be several times the bytes, and would put a parse and a
 * garbage-collection pass between the wire and the screen on every frame.
 *
 * <p>The endpoint itself does almost nothing -- frames are pushed by
 * {@link VizHub}, which finds live connections through {@code OpenConnections}
 * rather than through anything captured here. That is not a style choice: the
 * {@link WebSocketConnection} injected into an endpoint is a context-bound
 * proxy, and resolving it from the visualization thread would fail with no
 * active context.
 */
@WebSocket(path = "/ws/viz", endpointId = VizSocket.ID)
public class VizSocket {

    private static final Logger LOG = Logger.getLogger(VizSocket.class);

    public static final String ID = "dkm-viz";

    @Inject
    VizHub hub;

    @Inject
    WebSocketConnection connection;

    /**
     * Required by the extension: an endpoint must declare at least one lifecycle
     * or message method. There is genuinely nothing to do on open -- visualization frames
     * are pushed, never requested -- so this only records that a viewer arrived.
     */
    @OnOpen
    public void onOpen() {
        LOG.debugf("viz subscriber %s attached", connection.id());
    }

    @OnClose
    public void onClose() {
        hub.forget(connection.id());
    }
}
