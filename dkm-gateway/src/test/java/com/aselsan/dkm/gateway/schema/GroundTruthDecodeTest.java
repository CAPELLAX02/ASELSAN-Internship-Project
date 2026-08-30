package com.aselsan.dkm.gateway.schema;

import com.aselsan.dkm.gateway.model.MessageEntry;
import com.aselsan.dkm.gateway.model.MessageSet;
import com.fasterxml.jackson.databind.JsonNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FR-5a: check the schema engine against the binaries this repo already has,
 * rather than against itself.
 *
 * <p>{@code input.bin} was written by {@code bin_gen} with a hard-coded sequence,
 * and {@code output.bin} is what mock_r actually produced from it. Both are
 * independent of this code, so agreeing with them is real evidence that the
 * decoder matches the C++ side -- the same evidence {@code c_sim} provides, from
 * the other direction.
 */
class GroundTruthDecodeTest {

    private static final double EPSILON = 1e-9;

    private MessageSet load(Path path) throws IOException {
        SchemaModel schema = TestSchema.model();
        MessageCodec codec = TestSchema.codec();
        byte[] bytes = Files.readAllBytes(path);
        ByteBuf buf = Unpooled.buffer(bytes.length).writeBytes(bytes);
        MessageSet set = new MessageSet(schema, codec, 1024);
        MessageSet.ParseResult result = set.adopt(buf, path.getFileName().toString());
        assertEquals(bytes.length, result.bytesConsumed(),
                "framing on msg_length alone must consume the file exactly");
        return set;
    }

    @Test
    @DisplayName("input.bin decodes to exactly what bin_gen wrote")
    void inputBinMatchesBinGen() throws IOException {
        assumeTrue(TestSchema.mockAvailable(), "mock_r checkout not present");
        Path path = TestSchema.mockRoot().resolve("input.bin");
        assumeTrue(Files.isReadable(path), "input.bin not present");

        MessageSet set = load(path);
        MessageCodec codec = TestSchema.codec();
        List<MessageEntry> entries = set.entries();
        assertEquals(10, entries.size(), "bin_gen writes ten messages");

        assertEquals(List.of(
                        "RSM/GateAreaMsg", "RSM/ReportingAreaMsg", "RSM/ReadCommand",
                        "RSM/BeamReport", "RSM/BeamReport",
                        "RSP/DetectionReport", "RSP/DetectionReport", "RSP/DetectionReport",
                        "RSP/JammerReport", "CRM/Prediction"),
                entries.stream().map(e -> e.typeName).toList());

        // bin_gen spaces its timestamps 500 ms apart starting at 1000.
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(1000L + 500L * i, entries.get(i).timestamp, "timestamp of message " + i);
        }

        JsonNode gate = payload(codec, set, entries.get(0));
        assertEquals(500.0, gate.get("start_distance").asDouble(), EPSILON);
        assertEquals(600.0, gate.get("end_distance").asDouble(), EPSILON);
        assertEquals(1.0, gate.get("start_heading").asDouble(), EPSILON);
        assertEquals(1.2, gate.get("end_heading").asDouble(), EPSILON);

        JsonNode area = payload(codec, set, entries.get(1));
        assertEquals(-1000.0, area.get("start_x").asDouble(), EPSILON);
        assertEquals(1000.0, area.get("end_x").asDouble(), EPSILON);

        assertEquals(0, payload(codec, set, entries.get(2)).size(), "ReadCommand carries no payload fields");

        JsonNode beam2 = payload(codec, set, entries.get(4));
        assertEquals(2, beam2.get("beam_id").asLong());
        assertEquals(2100, beam2.get("beam_timestamp").asLong());
        assertEquals(1, beam2.get("beam_type").asLong());
        assertEquals(0.8, beam2.get("beam_heading").asDouble(), EPSILON);

        JsonNode detection = payload(codec, set, entries.get(5));
        assertEquals(1, detection.get("beam_id").asLong());
        assertEquals(2050, detection.get("detection_timestamp").asLong());
        assertEquals(3, detection.get("detection_count").asLong());
        JsonNode detections = detection.get("detections");
        assertEquals(8, detections.size(), "the array is always its declared length; the count says how many are live");
        assertEquals(90.0, detections.get(0).get("distance").asDouble(), EPSILON);
        assertEquals(0.35, detections.get(0).get("heading").asDouble(), EPSILON);
        assertEquals(110.0, detections.get(2).get("distance").asDouble(), EPSILON);
        assertEquals(0.45, detections.get(2).get("heading").asDouble(), EPSILON);
        assertEquals(0.0, detections.get(3).get("distance").asDouble(), EPSILON, "unused slots are zeroed");

        JsonNode jammer = payload(codec, set, entries.get(8));
        assertEquals(1, jammer.get("beam_id").asLong());
        assertEquals(2300, jammer.get("jammer_timestamp").asLong());
    }

    @Test
    @DisplayName("the stale Prediction in input.bin is reported, not silently mis-decoded")
    void staleMessageIsFlagged() throws IOException {
        assumeTrue(TestSchema.mockAvailable(), "mock_r checkout not present");
        Path path = TestSchema.mockRoot().resolve("input.bin");
        assumeTrue(Files.isReadable(path), "input.bin not present");

        MessageSet set = load(path);
        MessageEntry prediction = set.entries().get(9);

        // This file was generated before track_id was added to Prediction, so it
        // is 88 bytes where the current interface says 96. Framing still works --
        // msg_length is authoritative and needs no type table -- but the message
        // must not be decoded as if the missing field were there.
        assertEquals("CRM/Prediction", prediction.typeName);
        assertEquals(88, prediction.length);
        assertNotNull(prediction.problem, "a length that disagrees with the schema has to be reported (NFR-5)");
        assertTrue(prediction.problem.contains("96") && prediction.problem.contains("88"),
                "the report should name both lengths: " + prediction.problem);

        CodecException failure = org.junit.jupiter.api.Assertions.assertThrows(CodecException.class,
                () -> TestSchema.codec().decode(TestSchema.model().message("CRM/Prediction"),
                        set.view(prediction), 0, prediction.length));
        assertTrue(failure.getMessage().contains("predates"));
    }

    @Test
    @DisplayName("output.bin decodes to the measurements mock_r actually computed")
    void outputBinMatchesMockR() throws IOException {
        assumeTrue(TestSchema.mockAvailable(), "mock_r checkout not present");
        Path path = TestSchema.mockRoot().resolve("output.bin");
        assumeTrue(Files.isReadable(path), "output.bin not present");

        MessageSet set = load(path);
        MessageCodec codec = TestSchema.codec();
        List<MessageEntry> entries = set.entries();
        assertEquals(2, entries.size(),
                "three DetectionReports went in, but the one naming an unannounced beam produces nothing");

        for (MessageEntry entry : entries) {
            assertEquals("RSM/MeasurementReport", entry.typeName);
            assertNull(entry.problem);
            // Captured output has sender_id = RDP; the link is still resolved
            // correctly, from receiver_id.
            assertEquals(3, entry.moduleId, "these came back on the RSM link");
        }

        // beam_type 0 -> the three inputs are averaged.
        JsonNode first = payload(codec, set, entries.get(0));
        assertEquals(2050, first.get("measurement_timestamp").asLong());
        assertEquals(100.0, first.get("distance").asDouble(), EPSILON);
        assertEquals(0.4, first.get("heading").asDouble(), 1e-12);

        // beam_type 1 -> the input closest to the average is chosen instead.
        JsonNode second = payload(codec, set, entries.get(1));
        assertEquals(2150, second.get("measurement_timestamp").asLong());
        assertEquals(205.0, second.get("distance").asDouble(), EPSILON);
        assertEquals(0.8, second.get("heading").asDouble(), 1e-12);
    }

    private JsonNode payload(MessageCodec codec, MessageSet set, MessageEntry entry) {
        CompiledMessage type = TestSchemaTypes.of(entry);
        return codec.decode(type, set.view(entry), 0, entry.length).get("payload");
    }

    /** Tiny indirection so the payload helper stays readable. */
    private static final class TestSchemaTypes {
        static CompiledMessage of(MessageEntry entry) {
            try {
                return TestSchema.model().message(entry.typeName);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
