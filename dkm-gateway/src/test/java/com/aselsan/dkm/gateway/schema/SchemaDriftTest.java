package com.aselsan.dkm.gateway.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The interface-sync mechanism, made into a build failure.
 *
 * <p>§8 asks whether the schema should be hand-maintained or generated from the
 * headers. This is the answer: it is hand-maintained -- because direction,
 * units, documentation and correlation ids simply are not in the headers -- and
 * a generator runs on every build to prove the structural half still matches.
 * Add a field to a struct on the C++ side and this test goes red with the
 * difference spelled out, instead of the simulator quietly sending a message
 * that is now the wrong length.
 *
 * <p>When the headers are not in this checkout the test skips rather than
 * passes vacuously, so a CI job that never sees them cannot report green for a
 * check it did not perform.
 */
class SchemaDriftTest {

    @Test
    @DisplayName("the shipped schema still matches mock_r's headers")
    void schemaMatchesHeaders() throws IOException {
        assumeTrue(TestSchema.mockAvailable(),
                "mock_r headers are not in this checkout -- structural drift cannot be checked here");

        Path includeDir = TestSchema.mockRoot().resolve("mock_r/inc/interface");
        ObjectNode generated = HeaderSchemaGenerator.forMockR().generate(includeDir);
        JsonNode shipped = TestSchema.MAPPER.readTree(TestSchema.source());

        assertEquals(canonical(generated, true), canonical(shipped, false),
                """
                The interface headers and interface-schema.json no longer agree.

                Regenerate the structural half and fold the difference into
                src/main/resources/interface/interface-schema.json, keeping the
                annotations (direction, unit, doc, enumValues, correlationId)
                that the headers cannot express:

                  mvn -q exec:java -Dexec.mainClass=com.aselsan.dkm.gateway.schema.HeaderSchemaGenerator \\
                      -Dexec.args=../dkm-simulator/mock_r/inc/interface

                Left is what the headers say; right is what the schema claims.
                """);
    }

    /**
     * Reduces a schema to only what the headers can actually express, so the
     * comparison is about layout and never about annotations.
     */
    private static String canonical(JsonNode schema, boolean generated) {
        StringBuilder text = new StringBuilder();

        text.append("constants:\n");
        Map<String, String> constants = new TreeMap<>();
        JsonNode constantsNode = schema.get("constants");
        if (constantsNode != null) {
            constantsNode.properties().forEach(e -> constants.put(e.getKey(), e.getValue().asText()));
        }
        constants.forEach((k, v) -> text.append("  ").append(k).append(" = ").append(v).append('\n'));

        text.append("modules:\n");
        Map<String, String> modules = new TreeMap<>();
        for (JsonNode module : schema.path("modules")) {
            modules.put(module.path("name").asText(), module.path("id").asText());
        }
        modules.forEach((k, v) -> text.append("  ").append(k).append(" = ").append(v).append('\n'));

        text.append("header ").append(schema.path("header").path("name").asText()).append(":\n");
        appendFields(text, schema.path("header").path("fields"));

        text.append("structs:\n");
        Map<String, String> structs = new TreeMap<>();
        for (JsonNode struct : schema.path("structs")) {
            StringBuilder one = new StringBuilder();
            appendFields(one, struct.path("fields"));
            structs.put(struct.path("name").asText(), one.toString());
        }
        structs.forEach((name, body) -> text.append("  ").append(name).append(":\n").append(body));

        text.append("messages:\n");
        Map<String, String> messages = new TreeMap<>();
        for (JsonNode message : schema.path("messages")) {
            StringBuilder one = new StringBuilder();
            one.append("    module=").append(message.path("module").asText())
                    .append(" msgId=").append(message.path("msgId").asText()).append('\n');
            appendFields(one, message.path("fields"));
            messages.put(message.path("name").asText(), one.toString());
        }
        messages.forEach((name, body) -> text.append("  ").append(name).append(":\n").append(body));

        return text.toString();
    }

    private static void appendFields(StringBuilder text, JsonNode fields) {
        List<String> lines = new ArrayList<>();
        for (JsonNode field : fields) {
            StringBuilder line = new StringBuilder("    ");
            line.append(field.path("name").asText()).append(": ").append(field.path("type").asText());
            JsonNode array = field.get("array");
            if (array != null && !array.isNull()) {
                String constant = array.path("lengthConstant").asText("");
                line.append('[').append(constant.isEmpty() ? array.path("length").asText() : constant).append(']');
            }
            lines.add(line.toString());
        }
        lines.forEach(line -> text.append(line).append('\n'));
    }
}
