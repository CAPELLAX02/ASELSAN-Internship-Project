package com.aselsan.dkm.gateway.net;

import com.aselsan.dkm.gateway.events.EventHub;
import com.aselsan.dkm.gateway.schema.ModuleDef;
import com.aselsan.dkm.gateway.schema.SchemaModel;
import com.aselsan.dkm.gateway.schema.SchemaService;
import io.netty.buffer.ByteBuf;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns one TCP server per peer module and brings them all up at boot.
 *
 * <p>Binding at boot rather than on "start playback" is not an optimisation, it
 * is a correctness requirement: the DKM's connect happens once at <em>its</em>
 * startup and is never retried, so a simulator that only starts listening when
 * the operator presses play would routinely miss the connection entirely
 * (FR-17). The consequence is that "connected" is a state the operator observes,
 * never one the simulator initiates.
 */
@ApplicationScoped
public class LinkRegistry {

    private static final Logger LOG = Logger.getLogger(LinkRegistry.class);

    @Inject
    Vertx vertx;

    @Inject
    SchemaService schemaService;

    @Inject
    LinksConfig config;

    @Inject
    EventHub events;

    @Inject
    InboundSink inboundSink;

    private final List<Link> links = new ArrayList<>();

    /**
     * Runs at the default application priority. Anything that needs the links
     * to exist -- metrics, for one -- observes at a higher value so CDI runs it
     * afterwards; observer order is otherwise unspecified, and "unspecified"
     * here meant the per-link metrics silently never registered.
     */
    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION) StartupEvent event) {
        SchemaModel schema = schemaService.model();
        int index = 0;
        for (ModuleDef module : schema.peerModules()) {
            Integer override = config.port().get(module.name());
            int port = override != null ? override : module.defaultPort();
            if (port <= 0) {
                LOG.warnf("module %s has no port configured and no default in the schema -- skipping", module.name());
                continue;
            }
            Link link = new Link(index++, module, config.host(), port);
            link.splitter = newSplitter();
            links.add(link);
        }
        for (Link link : links) {
            bind(link);
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        for (Link link : links) {
            NetServer server = link.server;
            if (server != null) {
                server.close();
            }
            link.splitter.reset();
        }
    }

    private FrameSplitter newSplitter() {
        SchemaModel schema = schemaService.model();
        return new FrameSplitter(schemaService.wire(), schema.headerSize(), schema.msgLengthOffset(),
                schema.sizeTBytes(), config.maxMessageBytes());
    }

    private void bind(Link link) {
        NetServerOptions options = new NetServerOptions()
                .setHost(link.host)
                .setPort(link.port)
                .setTcpNoDelay(true)
                .setTcpKeepAlive(true)
                .setReuseAddress(true)
                .setSendBufferSize(config.sendBufferBytes())
                .setReceiveBufferSize(config.receiveBufferBytes());
        config.acceptBacklog().ifPresent(options::setAcceptBacklog);

        NetServer server = vertx.createNetServer(options);
        server.connectHandler(socket -> onConnect(link, socket));
        link.server = server;

        server.listen().onComplete(result -> {
            if (result.succeeded()) {
                link.state = LinkState.LISTENING;
                link.detail = "listening on " + link.host + ":" + link.port;
                events.info(link.name(), "listening on " + link.host + ":" + link.port
                        + " -- start the DKM now; it connects once and does not retry");
            } else {
                link.state = LinkState.FAILED;
                link.detail = "bind failed: " + result.cause().getMessage();
                events.error(link.name(), "failed to bind " + link.host + ":" + link.port
                        + " -- " + result.cause().getMessage());
            }
            publishState(link);
        });
    }

    private void onConnect(Link link, NetSocket socket) {
        NetSocket previous = link.socket;
        if (previous != null) {
            events.warn(link.name(), "a second connection arrived from " + socket.remoteAddress()
                    + " while " + link.peerAddress + " was still attached -- adopting the new one");
            previous.close();
            link.splitter.reset();
            link.splitter = newSplitter();
        }

        socket.setWriteQueueMaxSize(config.writeQueueMaxBytes());
        socket.drainHandler(v -> link.signalDrain());
        socket.handler(buffer -> onData(link, buffer));
        socket.exceptionHandler(t -> {
            link.detail = "socket error: " + t.getMessage();
            events.error(link.name(), "socket error -- " + t);
        });
        socket.closeHandler(v -> onClose(link, socket));

        link.socket = socket;
        link.peerAddress = String.valueOf(socket.remoteAddress());
        link.connectedAtMillis = System.currentTimeMillis();
        link.state = LinkState.CONNECTED;
        link.detail = "connected from " + link.peerAddress;
        events.info(link.name(), "DKM connected from " + link.peerAddress);
        publishState(link);
    }

    private void onData(Link link, io.vertx.core.buffer.Buffer buffer) {
        ByteBuf buf = buffer.getByteBuf();
        link.bytesIn.add(buf.readableBytes());
        try {
            link.splitter.feed(buf, (src, offset, length) -> {
                link.messagesIn.increment();
                inboundSink.onInbound(link, src, offset, length);
            });
        } catch (FrameSplitter.DesyncException e) {
            link.state = LinkState.FAILED;
            link.detail = e.getMessage();
            events.error(link.name(), "framing lost -- " + e.getMessage()
                    + ". Closing the link; a byte stream with no delimiters cannot be resynchronised.");
            publishState(link);
            NetSocket socket = link.socket;
            if (socket != null) {
                socket.close();
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "%s: unexpected failure handling inbound data", link.name());
            events.error(link.name(), "inbound handling failed -- " + e);
        }
    }

    private void onClose(Link link, NetSocket socket) {
        if (link.socket != socket) {
            return; // a stale socket we already replaced
        }
        link.socket = null;
        if (link.state != LinkState.FAILED) {
            link.state = LinkState.CLOSED;
            link.detail = "peer disconnected";
        }
        events.warn(link.name(), "DKM disconnected");
        publishState(link);
    }

    private void publishState(Link link) {
        events.publish("link", data -> {
            data.put("name", link.name());
            data.put("moduleId", link.moduleId());
            data.put("state", link.state.name());
            data.put("detail", link.detail);
            data.put("host", link.host);
            data.put("port", link.port);
            data.put("peer", link.peerAddress);
        });
    }

    public List<Link> links() {
        return links;
    }

}
