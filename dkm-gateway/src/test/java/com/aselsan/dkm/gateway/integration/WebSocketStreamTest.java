package com.aselsan.dkm.gateway.integration;

import com.aselsan.dkm.gateway.schema.SchemaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises both WebSocket channels with a real client.
 *
 * <p>This exists because of a bug that every other test in this suite happily
 * ignored: the hubs were pushing through the {@code WebSocketConnection} CDI
 * proxy, which resolves against a per-connection context. Publishers run on
 * link event loops, the replay pacer and the capture thread -- none of which
 * have that context -- so every push failed, on a background thread, into a
 * debug log. The REST API stayed perfectly correct and the browser showed
 * nothing at all.
 *
 * <p>The lesson generalises: a push channel is only tested by something that
 * actually receives a push.
 */
@QuarkusTest
class WebSocketStreamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HOST = "127.0.0.1";

    @Inject
    SchemaService schemaService;

    @TestHTTPResource
    URL root;

    private Api api;

    @BeforeEach
    void reset() {
        api = new Api(root);
        api.post("/api/playback/stop?rewind=true");
        api.post("/api/capture/clear");
        api.post("/api/session/clear");
    }

    private URI wsUri(String path) {
        return URI.create("ws://" + root.getHost() + ":" + root.getPort() + path);
    }

    @Test
    @DisplayName("control events reach a connected client, from threads with no request context")
    void eventsArrive() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(wsUri("/ws/events"), new TextCollector(received))
                .get(5, TimeUnit.SECONDS);
        try {
            // Anything that writes a log line publishes an event. This one is
            // emitted from a REST worker thread; the capture and playback events
            // below come from threads that have no CDI request context at all.
            api.post("/api/capture/clear");
            assertTrue(await(() -> received.stream().anyMatch(text -> text.contains("\"type\":\"log\"")), 5000),
                    "no log event arrived over the WebSocket: " + received);

            JsonNode event = MAPPER.readTree(received.stream()
                    .filter(text -> text.contains("\"type\":\"log\"")).findFirst().orElseThrow());
            assertEquals("log", event.path("type").asText());
            assertTrue(event.path("data").hasNonNull("message"));
        } finally {
            socket.abort();
        }
    }

    @Test
    @DisplayName("a replay produces binary visualization frames a client can decode")
    void vizFramesArrive() throws Exception {
        Path input = mockInput();
        assumeTrue(input != null, "input.bin not present in this checkout");

        List<ByteBuffer> frames = new CopyOnWriteArrayList<>();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(wsUri("/ws/viz"), new BinaryCollector(frames))
                .get(5, TimeUnit.SECONDS);

        var schema = schemaService.model();
        try (FakeDkm dkm = new FakeDkm(schema.headerSize(), schema.msgIdOffset(),
                schema.timestampOffset(), schema.msgLengthOffset())) {
            dkm.connect("RSP", HOST, 15001);
            dkm.connect("RSM", HOST, 15002);
            dkm.connect("CRM", HOST, 15003);

            assertEquals(200, api.post("/api/session/load-path",
                    "{\"path\": \"" + input + "\"}").status());
            api.put("/api/playback/speed", "{\"speed\": 50}");
            assertEquals(200, api.post("/api/playback/start").status());

            assertTrue(await(() -> !frames.isEmpty(), 8000),
                    "no visualization frame arrived while a replay was running");

            ByteBuffer frame = frames.get(0).duplicate().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0x444b4d56, frame.getInt(0), "frame magic");
            assertEquals(1, frame.getShort(4), "protocol version");
            int records = frame.getInt(8);
            assertTrue(records > 0, "a frame was sent with no samples in it");
            assertEquals(24 + records * 48, frame.remaining(),
                    "the frame length must match its declared record count");

            // Record layout, as the browser reads it.
            int base = 24;
            int link = frame.get(base + 6);
            int kind = frame.get(base + 7);
            assertTrue(link >= 0 && link < 3, "link index out of range: " + link);
            assertTrue(kind > 0 && kind <= 6, "unexpected visual kind: " + kind);
            assertTrue(Double.isFinite(frame.getDouble(base + 32)), "timestamp must be finite");
        } finally {
            socket.abort();
        }
    }

    private static boolean await(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    private static Path mockInput() {
        Path direct = Path.of("../dkm-simulator/input.bin").toAbsolutePath().normalize();
        if (Files.isReadable(direct)) {
            return direct;
        }
        Path nested = Path.of("dkm-simulator/input.bin").toAbsolutePath().normalize();
        return Files.isReadable(nested) ? nested : null;
    }

    private record TextCollector(List<String> into) implements WebSocket.Listener {
        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            into.add(data.toString());
            socket.request(1);
            return null;
        }
    }

    private record BinaryCollector(List<ByteBuffer> into) implements WebSocket.Listener {
        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data).flip();
            into.add(copy.order(ByteOrder.LITTLE_ENDIAN));
            socket.request(1);
            return null;
        }
    }
}
