package com.aselsan.dkm.gateway.schema;

import com.aselsan.dkm.gateway.wire.Wire;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads the shipped schema without needing a Quarkus container. */
public final class TestSchema {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private TestSchema() {
    }

    public static String source() throws IOException {
        try (InputStream in = TestSchema.class.getClassLoader()
                .getResourceAsStream(SchemaService.CLASSPATH_SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException("schema resource missing from the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static SchemaModel model() throws IOException {
        String source = source();
        return new SchemaCompiler(8, true).compile(MAPPER.readTree(source), source);
    }

    public static MessageCodec codec() throws IOException {
        return new MessageCodec(model(), new Wire(true));
    }

    /** The mock_r headers and sample binaries, when this checkout has them. */
    public static Path mockRoot() {
        Path direct = Path.of("../dkm-simulator");
        return Files.isDirectory(direct) ? direct : Path.of("dkm-simulator");
    }

    public static boolean mockAvailable() {
        return Files.isDirectory(mockRoot().resolve("mock_r/inc/interface"));
    }
}
