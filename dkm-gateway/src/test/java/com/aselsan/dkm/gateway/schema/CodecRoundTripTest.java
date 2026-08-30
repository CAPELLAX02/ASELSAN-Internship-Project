package com.aselsan.dkm.gateway.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** NFR-4: decoding and re-encoding an untouched message must give back the same bytes. */
class CodecRoundTripTest {

    @Test
    @DisplayName("every message in input.bin round-trips byte for byte")
    void roundTripsExactly() throws IOException {
        assumeTrue(TestSchema.mockAvailable(), "mock_r checkout not present");
        Path path = TestSchema.mockRoot().resolve("input.bin");
        assumeTrue(Files.isReadable(path), "input.bin not present");

        SchemaModel schema = TestSchema.model();
        MessageCodec codec = TestSchema.codec();
        byte[] file = Files.readAllBytes(path);

        int cursor = 0;
        int checked = 0;
        while (cursor < file.length) {
            ByteBuf view = Unpooled.wrappedBuffer(file, cursor, file.length - cursor);
            int length = (int) codec.msgLength(view, 0);
            long senderId = codec.senderId(view, 0);
            long receiverId = codec.receiverId(view, 0);
            ModuleDef peer = schema.resolvePeer(senderId, receiverId);
            CompiledMessage type = peer == null ? null : schema.message(peer.id(), codec.msgId(view, 0));

            if (type != null && type.size == length) {
                byte[] original = new byte[length];
                System.arraycopy(file, cursor, original, 0, length);

                JsonNode decoded = codec.decode(type, Unpooled.wrappedBuffer(original), 0, length);
                byte[] reencoded = original.clone();
                codec.encode(type, decoded, Unpooled.wrappedBuffer(reencoded), 0);

                assertArrayEquals(original, reencoded,
                        type.qualifiedName + " at byte " + cursor + " did not survive a decode/encode round trip");
                checked++;
            }
            cursor += length;
        }
        assertEquals(9, checked, "nine of the ten messages match the current schema; the stale Prediction does not");
    }

    @Test
    @DisplayName("an edit changes only the field that was edited")
    void editIsSurgical() throws IOException {
        SchemaModel schema = TestSchema.model();
        MessageCodec codec = TestSchema.codec();
        CompiledMessage type = schema.message("RSP/DetectionReport");

        byte[] bytes = new byte[type.size];
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        codec.writeHeader(buf, 0, 2, 1, type.msgId, 1234, type.size);
        // A byte the schema does not describe at all: nothing here should ever
        // touch it, which is what keeps a re-saved binary valid downstream.
        bytes[type.size - 1] = (byte) 0xAB;

        ObjectNode edit = (ObjectNode) TestSchema.MAPPER.readTree("""
                {"payload": {"beam_id": 7}}
                """);
        codec.encode(type, edit, buf, 0);

        JsonNode payload = codec.decode(type, buf, 0, type.size).get("payload");
        assertEquals(7, payload.get("beam_id").asLong());
        assertEquals(1234, codec.timestamp(buf, 0), "the header was not part of the edit");
        assertEquals((byte) 0xAB, bytes[type.size - 1], "bytes the schema does not describe must survive an edit");
    }

    @Test
    @DisplayName("out-of-range and over-long values are refused with the field named")
    void validationNamesTheField() throws IOException {
        SchemaModel schema = TestSchema.model();
        MessageCodec codec = TestSchema.codec();
        CompiledMessage type = schema.message("RSP/DetectionReport");

        JsonNode negative = TestSchema.MAPPER.readTree("""
                {"payload": {"beam_id": -1}}
                """);
        List<String> issues = codec.validate(type, negative);
        assertEquals(1, issues.size(), issues.toString());
        assertTrue(issues.get(0).contains("beam_id") && issues.get(0).contains("outside the range"),
                issues.toString());

        // FR-5: the count field and the live array length have to agree.
        JsonNode tooMany = TestSchema.MAPPER.readTree("""
                {"payload": {"detection_count": 3,
                             "detections": [{"distance": 1, "heading": 0}]}}
                """);
        List<String> countIssues = codec.validate(type, tooMany);
        assertTrue(countIssues.stream().anyMatch(i -> i.contains("detection_count") && i.contains("only 1")),
                countIssues.toString());

        JsonNode overflowing = TestSchema.MAPPER.readTree("""
                {"payload": {"detection_count": 99}}
                """);
        assertTrue(codec.validate(type, overflowing).isEmpty(),
                "99 is a legal usize; it is only wrong once an array is supplied to contradict it");
    }
}
