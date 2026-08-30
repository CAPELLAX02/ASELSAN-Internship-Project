package com.aselsan.dkm.gateway.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The layout the compiler resolves has to be the layout the DKM's compiler
 * produced, byte for byte. These sizes are cross-checked against the actual
 * {@code msg_length} values in this repo's sample binaries -- see
 * {@link GroundTruthDecodeTest}.
 */
class SchemaLayoutTest {

    @Test
    @DisplayName("header is five size_t fields at 8-byte spacing")
    void headerLayout() throws IOException {
        SchemaModel schema = TestSchema.model();
        assertEquals(40, schema.headerSize());
        assertEquals(0, schema.senderIdOffset());
        assertEquals(8, schema.receiverIdOffset());
        assertEquals(16, schema.msgIdOffset());
        assertEquals(24, schema.timestampOffset());
        assertEquals(32, schema.msgLengthOffset());
    }

    @Test
    @DisplayName("message sizes match sizeof() on the target")
    void messageSizes() throws IOException {
        SchemaModel schema = TestSchema.model();
        assertEquals(192, schema.message("RSP/DetectionReport").size);
        assertEquals(56, schema.message("RSP/JammerReport").size);
        assertEquals(72, schema.message("RSM/BeamReport").size);
        assertEquals(72, schema.message("RSM/GateAreaMsg").size);
        assertEquals(72, schema.message("RSM/ReportingAreaMsg").size);
        assertEquals(40, schema.message("RSM/ReadCommand").size, "a header-only message is just the header");
        assertEquals(64, schema.message("RSM/MeasurementReport").size);
        assertEquals(96, schema.message("CRM/Prediction").size);
    }

    @Test
    @DisplayName("payload offsets start after the header and respect alignment")
    void payloadOffsets() throws IOException {
        SchemaModel schema = TestSchema.model();
        CompiledMessage detection = schema.message("RSP/DetectionReport");
        assertEquals(40, detection.requireField("beam_id").offset);
        assertEquals(48, detection.requireField("detection_timestamp").offset);
        assertEquals(56, detection.requireField("detection_count").offset);

        CompiledField detections = detection.requireField("detections");
        assertEquals(64, detections.offset);
        assertEquals(8, detections.arrayLength);
        assertEquals(16, detections.elementSize, "Detection is two doubles with no padding");
        assertEquals(128, detections.size);
        assertEquals("detection_count", detections.countField);
        assertEquals(64 + 3 * 16 + 8, detections.elementOffset(3) + 8,
                "element 3's heading sits one double into element 3");
    }

    @Test
    @DisplayName("msg_id only has to be unique within a link")
    void msgIdIsPerLink() throws IOException {
        SchemaModel schema = TestSchema.model();
        // Both are msg_id 1 -- on different links, which is exactly how each
        // xxx_comm.cpp's dispatch() resolves them.
        assertEquals("RSM/BeamReport", schema.message(3, 1).qualifiedName);
        assertEquals("CRM/Prediction", schema.message(4, 1).qualifiedName);
        assertEquals("RSP/DetectionReport", schema.message(2, 1).qualifiedName);
    }

    @Test
    @DisplayName("a link is resolved the same way for stimulus and for captured output")
    void peerResolution() throws IOException {
        SchemaModel schema = TestSchema.model();
        // Stimulus: sender is the peer.
        assertEquals("RSM", schema.resolvePeer(3, 1).name());
        // Capture: sender is the DKM, so the peer is the receiver.
        assertEquals("RSM", schema.resolvePeer(1, 3).name());
        assertNull(schema.resolvePeer(1, 1), "DKM to DKM is not a link");
        assertNull(schema.resolvePeer(99, 1));
    }

    @Test
    @DisplayName("a 32-bit target moves every offset, from config alone")
    void targetDataModelDrivesLayout() throws IOException {
        String source = TestSchema.source();
        SchemaModel narrow = new SchemaCompiler(4, true).compile(TestSchema.MAPPER.readTree(source), source);
        assertEquals(20, narrow.headerSize(), "five 4-byte size_t fields");
        CompiledMessage detection = narrow.message("RSP/DetectionReport");
        assertEquals(20, detection.requireField("beam_id").offset);
        // beam_id/detection_timestamp/detection_count are 4 bytes each now, so
        // the cursor lands at 32 -- already 8-aligned, so Detection (still two
        // doubles) needs no extra padding.
        assertEquals(32, detection.requireField("detections").offset);
        assertEquals(32 + 8 * 16, detection.size);
        assertNotNull(narrow.message("CRM/Prediction"));
    }

    @Test
    @DisplayName("a schema that cannot be trusted fails at startup, not at send time")
    void badSchemaFailsFast() {
        String duplicate = """
                {
                  "version": "x",
                  "modules": [ {"name":"RDP","id":1,"role":"DKM"}, {"name":"RSP","id":2,"port":1} ],
                  "header": {"name":"MsgHeader","fields":[
                    {"name":"sender_id","type":"usize"},{"name":"receiver_id","type":"usize"},
                    {"name":"msg_id","type":"usize"},{"name":"timestamp","type":"usize"},
                    {"name":"msg_length","type":"usize"}]},
                  "messages": [
                    {"name":"A","module":"RSP","msgId":1,"fields":[]},
                    {"name":"B","module":"RSP","msgId":1,"fields":[]}
                  ]
                }
                """;
        SchemaException failure = assertThrows(SchemaException.class,
                () -> new SchemaCompiler(8, true).compile(TestSchema.MAPPER.readTree(duplicate), duplicate));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("msg_id"));
    }
}
