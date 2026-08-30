package com.aselsan.dkm.gateway.integration;

import com.aselsan.dkm.gateway.schema.CompiledMessage;
import com.aselsan.dkm.gateway.schema.SchemaService;
import com.fasterxml.jackson.databind.JsonNode;
import io.netty.buffer.Unpooled;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** FR-29 sorting and FR-32's single chronological trace. */
@QuarkusTest
class ListAndTraceTest {

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
        assumeTrue(mockInput() != null, "input.bin not present in this checkout");
        assertEquals(200, api.post("/api/session/load-path",
                "{\"path\": \"" + mockInput() + "\"}").status());
    }

    private List<Long> timestamps(JsonNode page) {
        List<Long> values = new ArrayList<>();
        page.path("items").forEach(item -> values.add(item.path("timestamp").asLong()));
        return values;
    }

    @Test
    @DisplayName("the list sorts by timestamp in both directions, across the whole set")
    void sortsByTimestamp() {
        List<Long> ascending = timestamps(api.get("/api/session/messages?sort=timestamp").body());
        assertEquals(10, ascending.size());
        for (int i = 1; i < ascending.size(); i++) {
            assertTrue(ascending.get(i - 1) <= ascending.get(i), "not ascending: " + ascending);
        }

        List<Long> descending = timestamps(
                api.get("/api/session/messages?sort=timestamp&dir=desc").body());
        for (int i = 1; i < descending.size(); i++) {
            assertTrue(descending.get(i - 1) >= descending.get(i), "not descending: " + descending);
        }
        assertEquals(ascending.get(0), descending.get(descending.size() - 1));
    }

    @Test
    @DisplayName("sorting by type groups the list and keeps each group chronological")
    void sortsByType() {
        JsonNode page = api.get("/api/session/messages?sort=type").body();
        String previousType = "";
        long previousTimestamp = 0;
        for (JsonNode item : page.path("items")) {
            String type = item.path("type").asText("");
            long timestamp = item.path("timestamp").asLong();
            assertTrue(type.compareTo(previousType) >= 0, "types out of order at " + type);
            if (type.equals(previousType)) {
                assertTrue(timestamp >= previousTimestamp,
                        "within a type the list should stay in list order");
            }
            previousType = type;
            previousTimestamp = timestamp;
        }
    }

    @Test
    @DisplayName("sorting a filtered list sorts the filter's results, not just the visible page")
    void sortingRespectsFilters() {
        JsonNode page = api.get("/api/session/messages?link=RSM&sort=length&dir=desc").body();
        assertTrue(page.path("filtered").asInt() > 0);
        int previous = Integer.MAX_VALUE;
        for (JsonNode item : page.path("items")) {
            assertEquals("RSM", item.path("link").asText());
            int length = item.path("length").asInt();
            assertTrue(length <= previous, "lengths not descending");
            previous = length;
        }
    }

    @Test
    @DisplayName("the trace interleaves what went out and what came back, in wall-clock order")
    void traceInterleavesBothDirections() throws Exception {
        var schema = schemaService.model();
        try (FakeDkm dkm = new FakeDkm(schema.headerSize(), schema.msgIdOffset(),
                schema.timestampOffset(), schema.msgLengthOffset())) {
            dkm.connect("RSP", "127.0.0.1", 15001);
            Socket rsm = dkm.connect("RSM", "127.0.0.1", 15002);
            dkm.connect("CRM", "127.0.0.1", 15003);

            api.put("/api/playback/speed", "{\"speed\": 50}");
            assertEquals(200, api.post("/api/playback/start").status());
            assertTrue(dkm.awaitMessages(9, 10_000), "replay did not complete");

            dkm.send(rsm, measurementReport());

            long deadline = System.currentTimeMillis() + 5000;
            JsonNode trace = null;
            while (System.currentTimeMillis() < deadline) {
                trace = api.get("/api/trace").body();
                boolean sawInbound = false;
                for (JsonNode item : trace.path("items")) {
                    sawInbound |= "IN".equals(item.path("direction").asText());
                }
                if (sawInbound) {
                    break;
                }
                Thread.sleep(50);
            }

            List<String> directions = new ArrayList<>();
            long previous = 0;
            for (JsonNode item : trace.path("items")) {
                directions.add(item.path("direction").asText());
                long wallClock = item.path("wallClock").asLong();
                assertTrue(wallClock >= previous, "trace is not in wall-clock order");
                previous = wallClock;
                assertTrue(item.path("deltaMillis").asLong() >= 0, "gap to the previous line went backwards");
            }
            assertTrue(directions.contains("OUT"), "nothing outbound in the trace: " + directions);
            assertTrue(directions.contains("IN"), "nothing inbound in the trace: " + directions);
            // The reply can only come after the stimulus that caused it.
            assertTrue(directions.indexOf("IN") > directions.indexOf("OUT"),
                    "the DKM's reply should follow the messages that produced it");

            assertEquals(9, api.get("/api/trace?direction=out").body().path("total").asInt(),
                    "every sent message should appear in the outbound trace");
        }
    }

    private byte[] measurementReport() {
        CompiledMessage type = schemaService.model().message("RSM/MeasurementReport");
        byte[] bytes = new byte[type.size];
        var buf = Unpooled.wrappedBuffer(bytes);
        var codec = schemaService.codec();
        codec.writeHeader(buf, 0, schemaService.model().dkmModule().id(),
                schemaService.model().moduleByName("RSM").id(), type.msgId, 4242, type.size);
        codec.wire().writeF64(buf, type.requireField("distance").offset, 100.0);
        return bytes;
    }

    private static Path mockInput() {
        Path direct = Path.of("../dkm-simulator/input.bin").toAbsolutePath().normalize();
        return Files.isReadable(direct) ? direct : null;
    }
}
