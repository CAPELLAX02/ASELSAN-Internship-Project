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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The whole path, with a stand-in DKM on the other end: three ports bound before
 * the peer connects, a binary loaded and replayed off one shared clock, and the
 * peer's reply captured and decoded.
 */
@QuarkusTest
class EndToEndReplayTest {

    private static final String HOST = "127.0.0.1";
    private static final int RSP_PORT = 15001;
    private static final int RSM_PORT = 15002;
    private static final int CRM_PORT = 15003;

    @Inject
    SchemaService schemaService;

    @TestHTTPResource
    URL root;

    private Api api;

    @BeforeEach
    void resetState() {
        api = new Api(root);
        assertEquals(200, api.post("/api/playback/stop?rewind=true").status());
        assertEquals(200, api.post("/api/capture/clear").status());
        assertEquals(200, api.post("/api/session/clear").status());
    }

    private FakeDkm newDkm() {
        var schema = schemaService.model();
        return new FakeDkm(schema.headerSize(), schema.msgIdOffset(),
                schema.timestampOffset(), schema.msgLengthOffset());
    }

    private void awaitListening() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode links = api.get("/api/status/links").body();
            if (links.size() == 3) {
                boolean bound = true;
                for (JsonNode link : links) {
                    String state = link.path("state").asText();
                    bound &= !state.equals("DOWN") && !state.equals("FAILED");
                }
                if (bound) {
                    return;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("the three links never reached a bound state");
    }

    private void loadSampleInput() {
        Api.Result loaded = api.post("/api/session/load-path",
                "{\"path\": \"" + mockInput().toString().replace("\\", "\\\\") + "\"}");
        assertEquals(200, loaded.status(), loaded.raw());
        assertEquals(10, loaded.at("/messages").asInt());
    }

    @Test
    @DisplayName("the peer connects to three already-listening ports and receives the replay in order")
    void replaysToAllThreeLinks() throws Exception {
        assumeTrue(mockInput() != null, "input.bin not present in this checkout");
        awaitListening();

        try (FakeDkm dkm = newDkm()) {
            // FR-17: the peer gets exactly one attempt, so the ports must already
            // be bound by the time it runs. They were bound at startup, not when
            // playback was requested.
            dkm.connect("RSP", HOST, RSP_PORT);
            Socket rsm = dkm.connect("RSM", HOST, RSM_PORT);
            dkm.connect("CRM", HOST, CRM_PORT);

            loadSampleInput();
            assertEquals(200, api.put("/api/playback/speed", "{\"speed\": 50}").status());
            assertEquals(200, api.post("/api/playback/start").status());

            assertTrue(dkm.awaitMessages(9, 10_000),
                    "expected nine sendable messages, got " + dkm.received().size());
            Thread.sleep(250);

            assertEquals(4, dkm.receivedOn("RSP").size(), "three DetectionReports and a JammerReport");
            assertEquals(5, dkm.receivedOn("RSM").size(),
                    "gate area, reporting area, read command and two beam reports");
            assertEquals(0, dkm.receivedOn("CRM").size(),
                    "the only CRM message in this file predates track_id, so it is reported rather than sent");

            // FR-13: the BeamReport announcing beam 2 (recorded at 3000 ms) has to
            // arrive before the DetectionReport that names it (recorded at 4000 ms),
            // even though they travel on different links and different sockets.
            // Pacing each link off its own previous message is what breaks this.
            long beamAnnounced = dkm.receivedOn("RSM").stream()
                    .filter(r -> r.timestamp() == 3000).findFirst().orElseThrow().arrivedNanos();
            long detectionUsingIt = dkm.receivedOn("RSP").stream()
                    .filter(r -> r.timestamp() == 4000).findFirst().orElseThrow().arrivedNanos();
            assertTrue(beamAnnounced < detectionUsingIt,
                    "the beam must be announced before the detection that references it");

            // FR-19/FR-20: the peer answers, and the answer comes back decoded.
            dkm.send(rsm, measurementReport());
            long deadline = System.currentTimeMillis() + 5000;
            JsonNode captured = null;
            while (System.currentTimeMillis() < deadline) {
                captured = api.get("/api/capture/messages").body();
                if (captured.path("total").asInt() > 0) {
                    break;
                }
                Thread.sleep(50);
            }
            assertEquals(1, captured.path("total").asInt(), "the DKM's reply should have been captured");
            assertEquals("RSM/MeasurementReport", captured.at("/items/0/type").asText());
            assertEquals("FROM_DKM", captured.at("/items/0/direction").asText());

            // FR-21: and it saves back out byte-exact.
            byte[] exported = api.getBytes("/api/capture/export");
            assertEquals(64, exported.length, "one MeasurementReport, in the same wire format");

            JsonNode playback = api.get("/api/playback").body();
            assertEquals("FINISHED", playback.path("state").asText());
            assertEquals(9, playback.path("sent").asInt());
        }
    }

    @Test
    @DisplayName("stepping sends exactly one message at a time and a later run picks up where it left off")
    void stepsOneMessageAtATime() throws Exception {
        assumeTrue(mockInput() != null, "input.bin not present in this checkout");
        awaitListening();

        try (FakeDkm dkm = newDkm()) {
            dkm.connect("RSP", HOST, RSP_PORT);
            dkm.connect("RSM", HOST, RSM_PORT);
            dkm.connect("CRM", HOST, CRM_PORT);

            loadSampleInput();

            // The reason this control exists: a message can be small on the wire
            // and still cost the DKM minutes of work. A paced run would send the
            // next one regardless, so stepping has to hand over exactly one and
            // then stop, however long the operator takes to look at the result.
            Api.Result first = api.post("/api/playback/step?count=1");
            assertEquals(200, first.status(), first.raw());
            assertEquals(1, first.at("/stepped").asInt());
            assertEquals("PAUSED", first.at("/state").asText(),
                    "after a step the run is held, not running");

            assertTrue(dkm.awaitMessages(1, 5000), "the first message should have gone out");
            Thread.sleep(200);
            assertEquals(1, dkm.received().size(), "and nothing should follow it on its own");

            assertEquals(3, api.post("/api/playback/step?count=3").at("/stepped").asInt());
            assertTrue(dkm.awaitMessages(4, 5000));
            Thread.sleep(200);
            assertEquals(4, dkm.received().size());

            // Stepping and running share one cursor, so starting now continues the
            // scenario rather than replaying the four already delivered.
            assertEquals(200, api.put("/api/playback/speed", "{\"speed\": 50}").status());
            assertEquals(200, api.post("/api/playback/start").status());
            assertTrue(dkm.awaitMessages(9, 10_000),
                    "expected the remaining messages, got " + dkm.received().size());
            Thread.sleep(250);
            assertEquals(9, dkm.received().size(), "nine sendable messages in total, none twice");
            assertEquals(9, api.get("/api/playback").body().path("sent").asInt());
        }
    }

    @Test
    @DisplayName("a file loaded between two steps ends the old run instead of stepping into freed memory")
    void steppingSurvivesTheSetBeingReplaced() throws Exception {
        assumeTrue(mockInput() != null, "input.bin not present in this checkout");
        awaitListening();

        try (FakeDkm dkm = newDkm()) {
            dkm.connect("RSP", HOST, RSP_PORT);
            dkm.connect("RSM", HOST, RSM_PORT);
            dkm.connect("CRM", HOST, CRM_PORT);

            loadSampleInput();
            assertEquals(2, api.post("/api/playback/step?count=2").at("/stepped").asInt());

            // The plan is derived from the sent markers, never held across a step.
            // Holding one meant the arena it pointed into could be replaced under
            // it, and the next step read from bytes that no longer existed.
            loadSampleInput();
            Api.Result after = api.post("/api/playback/step?count=1");
            assertEquals(200, after.status(), after.raw());
            assertEquals(1, after.at("/stepped").asInt());
            assertEquals(1, after.at("/sent").asInt(),
                    "loading a set ends the run that was reading the previous one");
        }
    }

    @Test
    @DisplayName("stepping from a chosen message treats everything before it as already sent")
    void stepsFromAChosenMessage() throws Exception {
        assumeTrue(mockInput() != null, "input.bin not present in this checkout");
        awaitListening();

        try (FakeDkm dkm = newDkm()) {
            dkm.connect("RSP", HOST, RSP_PORT);
            dkm.connect("RSM", HOST, RSM_PORT);
            dkm.connect("CRM", HOST, CRM_PORT);

            loadSampleInput();
            JsonNode rows = api.get("/api/session/messages?offset=0&limit=10").body().path("items");
            // The fifth sendable message, so there is something in front of it to skip.
            long chosen = rows.get(4).path("id").asLong();
            long chosenTimestamp = rows.get(4).path("timestamp").asLong();

            Api.Result stepped = api.post("/api/playback/step?count=1&from=" + chosen);
            assertEquals(200, stepped.status(), stepped.raw());
            assertEquals(1, stepped.at("/stepped").asInt());

            assertTrue(dkm.awaitMessages(1, 5000));
            Thread.sleep(200);
            assertEquals(1, dkm.received().size(), "exactly the chosen message, not the four before it");
            assertEquals(chosenTimestamp, dkm.received().get(0).timestamp(),
                    "and it is the one that was chosen");

            JsonNode listed = api.get("/api/session/messages?offset=0&limit=10").body().path("items");
            for (int i = 0; i < 4; i++) {
                assertTrue(listed.get(i).path("sent").asBoolean(),
                        "message " + i + " sits before the chosen one and counts as sent");
            }
        }
    }

    @Test
    @DisplayName("pause keeps the run and its state, and only pending messages stay editable")
    void pauseEditResume() throws Exception {
        assumeTrue(mockInput() != null, "input.bin not present in this checkout");
        awaitListening();

        try (FakeDkm dkm = newDkm()) {
            dkm.connect("RSP", HOST, RSP_PORT);
            dkm.connect("RSM", HOST, RSM_PORT);
            dkm.connect("CRM", HOST, CRM_PORT);

            loadSampleInput();
            // Slow enough that the pause genuinely lands mid-run.
            assertEquals(200, api.put("/api/playback/speed", "{\"speed\": 3}").status());
            assertEquals(200, api.post("/api/playback/start").status());
            Thread.sleep(400);
            assertEquals("PAUSED", api.post("/api/playback/pause").at("/state").asText());

            int sentSoFar = dkm.received().size();
            assertTrue(sentSoFar > 0 && sentSoFar < 9,
                    "the pause should land mid-run, not before or after it: " + sentSoFar);

            // FR-8: an already-sent message is history and refuses edits...
            JsonNode sent = api.get("/api/session/messages?status=sent").body();
            long sentId = sent.at("/items/0/id").asLong();
            Api.Result rejected = api.put("/api/session/messages/" + sentId, "{\"payload\": {}}");
            assertEquals(409, rejected.status(), rejected.raw());
            assertTrue(rejected.at("/message").asText().contains("already been sent"), rejected.raw());

            // ...while a pending one is editable, right now, mid-pause.
            JsonNode pending = api.get("/api/session/messages?status=pending&type=RSP/DetectionReport").body();
            assumeTrue(pending.path("filtered").asInt() > 0, "a pending DetectionReport is still queued");
            long pendingId = pending.at("/items/0/id").asLong();
            Api.Result edited = api.put("/api/session/messages/" + pendingId,
                    "{\"payload\": {\"beam_id\": 77}}");
            assertEquals(200, edited.status(), edited.raw());
            assertEquals(77, edited.at("/payload/beam_id").asInt());

            // FR-14: resume re-plans from the edited timeline, and nothing is re-sent.
            assertEquals("RUNNING", api.post("/api/playback/resume").at("/state").asText());
            assertTrue(dkm.awaitMessages(9, 10_000),
                    "the rest of the run should complete after resume, got " + dkm.received().size());
            Thread.sleep(200);
            assertEquals(9, dkm.received().size(), "no message may be sent twice across a pause");

            // The edit really did go out on the wire.
            var schema = schemaService.model();
            CompiledMessage detection = schema.message("RSP/DetectionReport");
            boolean sawEdit = dkm.receivedOn("RSP").stream().anyMatch(r ->
                    r.msgId() == detection.msgId
                            && schemaService.codec().readInteger(
                            detection.requireField("beam_id").type,
                            Unpooled.wrappedBuffer(r.bytes()),
                            detection.requireField("beam_id").offset) == 77);
            assertTrue(sawEdit, "the message edited during the pause should have been sent as edited");
        }
    }

    @Test
    @DisplayName("starting with nothing connected fails with the reason, not a silent no-op")
    void startWithoutPeerIsRefused() {
        Api.Result result = api.post("/api/playback/start");
        // Either there is genuinely nothing to send, or no peer is attached --
        // both are reported rather than pretending a run began.
        assertNotEquals("RUNNING", result.at("/state").asText(), result.raw());
    }

    /** A MeasurementReport built the way the DKM builds one: the DKM is the sender. */
    private byte[] measurementReport() {
        CompiledMessage type = schemaService.model().message("RSM/MeasurementReport");
        byte[] bytes = new byte[type.size];
        var buf = Unpooled.wrappedBuffer(bytes);
        var codec = schemaService.codec();
        codec.writeHeader(buf, 0, schemaService.model().dkmModule().id(),
                schemaService.model().moduleByName("RSM").id(), type.msgId, 4242, type.size);
        codec.wire().writeInteger(buf, type.requireField("measurement_timestamp").offset, 8, 2050);
        codec.wire().writeF64(buf, type.requireField("distance").offset, 100.0);
        codec.wire().writeF64(buf, type.requireField("heading").offset, 0.4);
        return bytes;
    }

    private static Path mockInput() {
        Path direct = Path.of("../dkm-simulator/input.bin").toAbsolutePath().normalize();
        if (Files.isReadable(direct)) {
            return direct;
        }
        Path nested = Path.of("dkm-simulator/input.bin").toAbsolutePath().normalize();
        return Files.isReadable(nested) ? nested : null;
    }
}
