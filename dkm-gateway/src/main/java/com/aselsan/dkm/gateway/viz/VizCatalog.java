package com.aselsan.dkm.gateway.viz;

import com.aselsan.dkm.gateway.schema.CompiledField;
import com.aselsan.dkm.gateway.schema.CompiledMessage;
import com.aselsan.dkm.gateway.schema.CompiledStruct;
import com.aselsan.dkm.gateway.schema.FieldType;
import com.aselsan.dkm.gateway.schema.SchemaException;
import com.aselsan.dkm.gateway.schema.SchemaModel;
import com.aselsan.dkm.gateway.schema.SchemaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Compiles {@code visualization.json} into a lookup from (link, msg_id) to a
 * ready-to-run {@link VizExtractor}.
 *
 * <p>Kept strictly separate from the interface schema (G8): the DKM's headers
 * describe what is on the wire, and nothing about how any of it should be drawn.
 * Changing a colour, or deciding a message type should render as a sector
 * instead of a point, is a change to this file alone -- no schema edit, no code
 * change, and no risk of a presentation decision leaking into the wire contract.
 */
@Startup
@ApplicationScoped
public class VizCatalog {

    private static final Logger LOG = Logger.getLogger(VizCatalog.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    public static final String CLASSPATH_CONFIG = "interface/visualization.json";

    @Inject
    ObjectMapper mapper;

    @Inject
    SchemaService schemaService;

    @ConfigProperty(name = "dkm.viz.path")
    Optional<String> configPath;

    private final Map<Long, VizExtractor> byModuleAndMsgId = new HashMap<>();
    private final Map<String, VizExtractor> byType = new LinkedHashMap<>();
    private ObjectNode description;
    private double maxRangeMeters = 2000;

    private BeamIndexSpec beamIndexSpec;

    /** Where the beam-heading lookup gets its data from. */
    public record BeamIndexSpec(CompiledMessage type, int keyOffset, FieldType keyType,
                                int valueOffset, FieldType valueType) {
    }

    @PostConstruct
    void load() {
        try {
            String source = readSource();
            JsonNode root = mapper.readTree(source);
            SchemaModel schema = schemaService.model();

            JsonNode defaults = root.path("defaults");
            maxRangeMeters = defaults.path("maxRangeMeters").asDouble(2000);
            double rayLength = defaults.path("rayLengthMeters").asDouble(maxRangeMeters);

            for (JsonNode mapping : root.path("mappings")) {
                compileMapping(schema, mapping, rayLength);
            }
            compileBeamIndex(schema, root.path("beamIndex"));

            description = buildDescription(root);
            LOG.infof("Visualization catalog %s compiled: %d mapping(s)",
                    root.path("version").asText("?"), byType.size());
        } catch (IOException e) {
            throw new SchemaException("failed to read the visualization catalog", e);
        }
    }

    private String readSource() throws IOException {
        if (configPath.isPresent() && !configPath.get().isBlank()) {
            return Files.readString(Path.of(configPath.get()), StandardCharsets.UTF_8);
        }
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(CLASSPATH_CONFIG)) {
            if (in == null) {
                throw new SchemaException("classpath resource " + CLASSPATH_CONFIG + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void compileMapping(SchemaModel schema, JsonNode mapping, double defaultRayLength) {
        String typeName = mapping.path("type").asText();
        CompiledMessage type = schema.message(typeName);
        if (type == null) {
            throw new SchemaException("visualization catalog references unknown message type " + typeName);
        }
        VizKind kind = VizKind.valueOf(mapping.path("kind").asText("NONE").toUpperCase(Locale.ROOT));
        boolean polar = "POLAR".equalsIgnoreCase(mapping.path("coordinates").asText("CARTESIAN"));
        int extraFlags = mapping.path("style").path("emphasis").asBoolean(false) ? VizRing.FLAG_EMPHASIS : 0;

        int arrayOffset = -1;
        int arrayStride = 0;
        int arrayLength = 0;
        int countOffset = -1;
        FieldType countType = null;
        JsonNode repeat = mapping.get("repeat");
        if (repeat != null) {
            CompiledField arrayField = type.requireField(repeat.path("array").asText());
            if (!arrayField.isArray()) {
                throw new SchemaException(typeName + ": repeat.array '" + arrayField.name + "' is not an array field");
            }
            arrayOffset = arrayField.offset;
            arrayStride = arrayField.elementSize;
            arrayLength = arrayField.arrayLength;
            String countFieldName = repeat.hasNonNull("countField")
                    ? repeat.get("countField").asText() : arrayField.countField;
            if (countFieldName != null) {
                CompiledField counter = type.requireField(countFieldName);
                countOffset = counter.offset;
                countType = counter.type;
            }
        }

        int correlationOffset = -1;
        FieldType correlationType = null;
        String correlationName = mapping.hasNonNull("correlation")
                ? mapping.get("correlation").asText()
                : (type.correlationField != null ? type.correlationField.name : null);
        if (correlationName != null) {
            CompiledField correlation = type.requireField(correlationName);
            correlationOffset = correlation.offset;
            correlationType = correlation.type;
        }

        Map<String, VizBinding> bindings = new LinkedHashMap<>();
        final int repeatArrayOffset = arrayOffset;
        JsonNode bindingsNode = mapping.path("bindings");
        bindingsNode.properties().forEach(entry ->
                bindings.put(entry.getKey(), compileBinding(type, entry.getValue(), repeatArrayOffset)));
        if (kind == VizKind.RAY && !bindings.containsKey("length")) {
            bindings.put("length", VizBinding.constant(defaultRayLength));
        }

        VizExtractor extractor = new VizExtractor(type, kind, polar, extraFlags, bindings,
                arrayOffset, arrayStride, arrayLength, countOffset, countType,
                correlationOffset, correlationType);
        byType.put(typeName, extractor);
        byModuleAndMsgId.put(key(type.module.id(), type.msgId), extractor);
    }

    private VizBinding compileBinding(CompiledMessage type, JsonNode node, int arrayOffset) {
        if (node.isNumber()) {
            return VizBinding.constant(node.asDouble());
        }
        if (node.isObject()) {
            if (node.hasNonNull("const")) {
                return VizBinding.constant(node.get("const").asDouble());
            }
            if ("BEAM_HEADING".equalsIgnoreCase(node.path("lookup").asText())) {
                CompiledField keyField = type.requireField(node.path("keyField").asText());
                return VizBinding.beamHeading(keyField.offset, keyField.type);
            }
            throw new SchemaException(type.qualifiedName + ": unrecognised binding " + node);
        }
        return resolvePath(type, node.asText(), arrayOffset);
    }

    /**
     * Resolves {@code "pos_x"}, {@code "detections[].distance"} or a nested
     * {@code "a.b"} to a byte offset and primitive type.
     */
    private VizBinding resolvePath(CompiledMessage type, String path, int arrayOffset) {
        int bracket = path.indexOf("[]");
        if (bracket < 0) {
            String[] parts = path.split("\\.");
            CompiledField field = type.requireField(parts[0]);
            int offset = field.offset;
            for (int i = 1; i < parts.length; i++) {
                if (field.struct == null) {
                    throw new SchemaException(type.qualifiedName + ": '" + path + "' descends into a non-struct field");
                }
                CompiledStruct struct = field.struct;
                field = struct.requireField(parts[i]);
                offset += field.offset;
            }
            requirePrimitive(type, path, field);
            return VizBinding.field(offset, field.type, false);
        }

        String arrayName = path.substring(0, bracket);
        String rest = path.substring(bracket + 2);
        CompiledField arrayField = type.requireField(arrayName);
        if (!arrayField.isArray()) {
            throw new SchemaException(type.qualifiedName + ": '" + arrayName + "' is not an array");
        }
        if (arrayField.offset != arrayOffset) {
            throw new SchemaException(type.qualifiedName + ": binding '" + path
                    + "' indexes '" + arrayName + "' but the mapping's repeat.array is a different field");
        }
        if (rest.isEmpty() || rest.equals(".")) {
            requirePrimitive(type, path, arrayField);
            return VizBinding.field(0, arrayField.type, true);
        }
        String[] parts = rest.startsWith(".") ? rest.substring(1).split("\\.") : rest.split("\\.");
        CompiledStruct struct = arrayField.struct;
        if (struct == null) {
            throw new SchemaException(type.qualifiedName + ": '" + arrayName + "' holds primitives, not structs");
        }
        CompiledField field = struct.requireField(parts[0]);
        int offset = field.offset;
        for (int i = 1; i < parts.length; i++) {
            struct = field.struct;
            if (struct == null) {
                throw new SchemaException(type.qualifiedName + ": '" + path + "' descends into a non-struct field");
            }
            field = struct.requireField(parts[i]);
            offset += field.offset;
        }
        requirePrimitive(type, path, field);
        return VizBinding.field(offset, field.type, true);
    }

    private static void requirePrimitive(CompiledMessage type, String path, CompiledField field) {
        if (field.type == null || field.type == FieldType.BOOL) {
            throw new SchemaException(type.qualifiedName + ": '" + path + "' is not a numeric field");
        }
    }

    private void compileBeamIndex(SchemaModel schema, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        CompiledMessage type = schema.message(node.path("type").asText());
        if (type == null) {
            LOG.warnf("beamIndex references unknown type %s -- beam-heading lookups will find nothing",
                    node.path("type").asText());
            return;
        }
        CompiledField key = type.requireField(node.path("keyField").asText());
        CompiledField value = type.requireField(node.path("valueField").asText());
        beamIndexSpec = new BeamIndexSpec(type, key.offset, key.type, value.offset, value.type);
    }

    private static long key(long moduleId, long msgId) {
        return (moduleId << 32) ^ (msgId & 0xFFFF_FFFFL);
    }

    public VizExtractor extractor(long moduleId, long msgId) {
        return byModuleAndMsgId.get(key(moduleId, msgId));
    }

    public VizExtractor extractor(String qualifiedName) {
        return byType.get(qualifiedName);
    }

    public BeamIndexSpec beamIndexSpec() {
        return beamIndexSpec;
    }

    /** The catalog as the UI consumes it: kinds, colours, labels, and the conventions in force. */
    public ObjectNode description() {
        return description;
    }

    private ObjectNode buildDescription(JsonNode root) {
        ObjectNode node = NODES.objectNode();
        node.put("version", root.path("version").asText("?"));
        node.set("conventions", root.path("conventions").deepCopy());
        node.set("defaults", root.path("defaults").deepCopy());
        ObjectNode mappings = node.putObject("mappings");
        for (JsonNode mapping : root.path("mappings")) {
            String typeName = mapping.path("type").asText();
            VizExtractor extractor = byType.get(typeName);
            ObjectNode entry = mappings.putObject(typeName);
            entry.put("kind", extractor == null ? "NONE" : extractor.kind.name());
            entry.put("kindCode", extractor == null ? VizKind.NONE.code() : extractor.kind.code());
            entry.put("coordinates", mapping.path("coordinates").asText("CARTESIAN"));
            entry.put("repeats", extractor != null && extractor.repeats());
            entry.set("style", mapping.path("style").deepCopy());
            if (mapping.hasNonNull("note")) {
                entry.put("note", mapping.get("note").asText());
            }
        }
        return node;
    }
}
