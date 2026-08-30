package com.aselsan.dkm.gateway.net;

import com.aselsan.dkm.gateway.schema.SchemaModel;
import com.aselsan.dkm.gateway.schema.TestSchema;
import com.aselsan.dkm.gateway.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCP delivers a byte stream, not messages. Whatever chunk sizes the kernel
 * happens to hand over -- one byte at a time, or a whole file in one read -- the
 * same message boundaries have to come out.
 */
class FrameSplitterTest {

    private FrameSplitter splitter(SchemaModel schema) {
        return new FrameSplitter(new Wire(true), schema.headerSize(), schema.msgLengthOffset(),
                schema.sizeTBytes(), 1 << 24);
    }

    @ParameterizedTest(name = "chunks of {0} byte(s)")
    @ValueSource(ints = {1, 3, 7, 40, 41, 64, 192, 193, 1023, 4096})
    @DisplayName("framing is independent of how the stream is chunked")
    void framesAcrossArbitraryChunks(int chunkSize) throws IOException {
        assumeTrue(TestSchema.mockAvailable(), "mock_r checkout not present");
        Path path = TestSchema.mockRoot().resolve("input.bin");
        assumeTrue(Files.isReadable(path), "input.bin not present");
        byte[] file = Files.readAllBytes(path);
        SchemaModel schema = TestSchema.model();

        List<byte[]> framed = new ArrayList<>();
        FrameSplitter splitter = splitter(schema);
        for (int at = 0; at < file.length; at += chunkSize) {
            int length = Math.min(chunkSize, file.length - at);
            ByteBuf chunk = Unpooled.wrappedBuffer(file, at, length);
            splitter.feed(chunk, (src, offset, len) -> {
                byte[] message = new byte[len];
                src.getBytes(offset, message);
                framed.add(message);
            });
        }
        assertEquals(0, splitter.pendingBytes(), "nothing should be left over from a complete file");
        assertEquals(10, framed.size());

        // Reassembling the frames must reproduce the file exactly.
        int cursor = 0;
        for (byte[] message : framed) {
            for (byte b : message) {
                assertEquals(file[cursor++], b);
            }
        }
        assertEquals(file.length, cursor);
        splitter.reset();
    }

    @Test
    @DisplayName("an impossible msg_length is fatal, not something to guess past")
    void desyncIsReported() throws IOException {
        SchemaModel schema = TestSchema.model();
        FrameSplitter splitter = splitter(schema);

        byte[] garbage = new byte[schema.headerSize()];
        // msg_length smaller than a header: there is no delimiter to resync on,
        // so continuing would mean reinterpreting every byte after this point.
        garbage[schema.msgLengthOffset()] = 4;

        FrameSplitter.DesyncException failure = assertThrows(FrameSplitter.DesyncException.class,
                () -> splitter.feed(Unpooled.wrappedBuffer(garbage), (src, offset, len) -> {
                }));
        assertTrue(failure.getMessage().contains("out of sync"));
        splitter.reset();
    }

    @Test
    @DisplayName("a message type the schema has never heard of still frames correctly")
    void unknownTypeStillFrames() throws IOException {
        SchemaModel schema = TestSchema.model();
        FrameSplitter splitter = splitter(schema);
        Wire wire = new Wire(true);

        int unknownLength = 123;
        byte[] stream = new byte[unknownLength + schema.headerSize()];
        ByteBuf buf = Unpooled.wrappedBuffer(stream);
        wire.writeInteger(buf, schema.msgLengthOffset(), 8, unknownLength);
        wire.writeInteger(buf, schema.msgIdOffset(), 8, 4242);
        wire.writeInteger(buf, unknownLength + schema.msgLengthOffset(), 8, schema.headerSize());

        List<Integer> lengths = new ArrayList<>();
        splitter.feed(Unpooled.wrappedBuffer(stream), (src, offset, len) -> lengths.add(len));
        assertEquals(List.of(unknownLength, schema.headerSize()), lengths,
                "msg_length alone is enough to frame a type this build does not know (FR-6)");
        splitter.reset();
    }
}
