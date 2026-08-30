package com.aselsan.dkm.gateway.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/**
 * Turns the declarative schema JSON into a {@link SchemaModel} with every byte
 * offset resolved.
 *
 * <p>This is the only place that knows C++ struct layout rules, and it runs
 * exactly once at startup. Everything downstream -- decode, encode, the field
 * editor the UI generates, the visualization extractors -- works off the
 * resolved offsets, so adding a message type is a JSON edit and nothing more
 * (G1 / NFR-1).
 *
 * <p>Layout rules implemented, matching every ABI the DKM plausibly builds for:
 * <ul>
 *   <li>a scalar is aligned to its own width;</li>
 *   <li>a struct is aligned to its most-aligned member;</li>
 *   <li>a struct's size is padded up to a multiple of its own alignment, so
 *       arrays of it stay aligned;</li>
 *   <li>{@code size_t} width comes from the target data model, not the schema.</li>
 * </ul>
 */
public final class SchemaCompiler {

    private final int sizeTBytes;
    private final boolean littleEndian;

    public SchemaCompiler(int sizeTBytes, boolean littleEndian) {
        if (sizeTBytes != 2 && sizeTBytes != 4 && sizeTBytes != 8) {
            throw new SchemaException("dkm.wire.size-t-bytes must be 2, 4 or 8 (got " + sizeTBytes + ")");
        }
        this.sizeTBytes = sizeTBytes;
        this.littleEndian = littleEndian;
    }

    public SchemaModel compile(JsonNode root, String rawSource) {
        String version = text(root, "version", "0.0.0");
        String hash = sha256(rawSource + "|sizeT=" + sizeTBytes + "|le=" + littleEndian);

        Map<String, Integer> constants = new LinkedHashMap<>();
        JsonNode constantsNode = root.get("constants");
        if (constantsNode != null) {
            constantsNode.properties().forEach(e -> constants.put(e.getKey(), e.getValue().asInt()));
        }

        List<ModuleDef> modules = compileModules(root.get("modules"));

        // Named structs first: message fields may reference them, and a struct
        // may reference an earlier one. Declaration order is the dependency
        // order -- a forward reference is a schema error, not a cycle to solve.
        Map<String, CompiledStruct> structs = new LinkedHashMap<>();
        JsonNode structsNode = root.get("structs");
        if (structsNode != null) {
            for (JsonNode s : structsNode) {
                String name = require(s, "name").asText();
                if (structs.containsKey(name)) {
                    throw new SchemaException("duplicate struct " + name);
                }
                structs.put(name, compileStruct(name, require(s, "fields"), structs, constants, 0));
            }
        }

        JsonNode headerNode = require(root, "header");
        CompiledStruct header = compileStruct(text(headerNode, "name", "MsgHeader"),
                require(headerNode, "fields"), structs, constants, 0);
        for (String needed : new String[]{"sender_id", "receiver_id", "msg_id", "timestamp", "msg_length"}) {
            header.requireField(needed);
        }

        List<CompiledMessage> messages = new ArrayList<>();
        for (JsonNode m : require(root, "messages")) {
            messages.add(compileMessage(m, header, structs, constants, modules));
        }

        return new SchemaModel(version, hash, sizeTBytes, littleEndian, header, modules,
                structs, messages, constants);
    }

    private List<ModuleDef> compileModules(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new SchemaException("schema must declare a non-empty 'modules' array");
        }
        List<ModuleDef> modules = new ArrayList<>(node.size());
        for (JsonNode m : node) {
            String name = require(m, "name").asText();
            long id = require(m, "id").asLong();
            boolean isDkm = "DKM".equalsIgnoreCase(text(m, "role", "PEER"));
            int port = m.hasNonNull("port") ? m.get("port").asInt() : -1;
            modules.add(new ModuleDef(name, id, isDkm, port, text(m, "description", "")));
        }
        return modules;
    }

    private CompiledMessage compileMessage(JsonNode node, CompiledStruct header,
                                           Map<String, CompiledStruct> structs,
                                           Map<String, Integer> constants,
                                           List<ModuleDef> modules) {
        String name = require(node, "name").asText();
        String moduleName = require(node, "module").asText();
        ModuleDef module = modules.stream()
                .filter(m -> m.name().equals(moduleName))
                .findFirst()
                .orElseThrow(() -> new SchemaException(name + " references unknown module " + moduleName));
        if (module.dkm()) {
            throw new SchemaException(name + " is assigned to the DKM module itself; assign it to the peer link it travels on");
        }
        long msgId = require(node, "msgId").asLong();
        Direction direction = Direction.valueOf(text(node, "direction", "TO_DKM").toUpperCase(Locale.ROOT));

        // Payload begins right after the header, at the header's own size --
        // the header is the message's first member, so its trailing padding
        // (if any) is already baked into header.size.
        CompiledStruct payload = compileStruct(name, require(node, "fields"), structs, constants, header.size);
        int alignment = Math.max(header.alignment, payload.alignment);
        int size = alignUp(header.size + payloadExtent(payload, header.size), alignment);

        return new CompiledMessage(module, name, msgId, direction, header, payload.fields,
                size, alignment, text(node, "doc", ""));
    }

    private static int payloadExtent(CompiledStruct payload, int headerSize) {
        int end = headerSize;
        for (CompiledField f : payload.fields) {
            end = Math.max(end, f.offset + f.size);
        }
        return end - headerSize;
    }

    private CompiledStruct compileStruct(String name, JsonNode fieldsNode,
                                         Map<String, CompiledStruct> structs,
                                         Map<String, Integer> constants,
                                         int baseOffset) {
        List<CompiledField> fields = new ArrayList<>();
        int cursor = baseOffset;
        int structAlign = 1;

        for (JsonNode f : fieldsNode) {
            String fieldName = require(f, "name").asText();
            String typeName = require(f, "type").asText();

            FieldType primitive = FieldType.parse(typeName);
            CompiledStruct nested = primitive == null ? structs.get(typeName) : null;
            if (primitive == null && nested == null) {
                throw new SchemaException(name + "." + fieldName + ": unknown type '" + typeName
                        + "' (not a primitive, and no struct with that name is declared before this point)");
            }

            int elementSize = primitive != null ? primitive.width(sizeTBytes) : nested.size;
            int alignment = primitive != null ? primitive.alignment(sizeTBytes) : nested.alignment;

            int arrayLength = 1;
            String countField = null;
            String lengthConstant = null;
            JsonNode arrayNode = f.get("array");
            boolean isArray = arrayNode != null;
            if (isArray) {
                lengthConstant = arrayNode.hasNonNull("lengthConstant")
                        ? arrayNode.get("lengthConstant").asText() : null;
                if (lengthConstant != null) {
                    Integer resolved = constants.get(lengthConstant);
                    if (resolved == null) {
                        throw new SchemaException(name + "." + fieldName + ": array lengthConstant '"
                                + lengthConstant + "' is not declared in 'constants'");
                    }
                    arrayLength = resolved;
                    if (arrayNode.hasNonNull("length") && arrayNode.get("length").asInt() != arrayLength) {
                        throw new SchemaException(name + "." + fieldName + ": declared length "
                                + arrayNode.get("length").asInt() + " disagrees with constant "
                                + lengthConstant + " = " + arrayLength);
                    }
                } else {
                    arrayLength = require(arrayNode, "length").asInt();
                }
                if (arrayLength <= 0) {
                    throw new SchemaException(name + "." + fieldName + ": array length must be > 0");
                }
                countField = arrayNode.hasNonNull("countField") ? arrayNode.get("countField").asText() : null;
            }

            int offset = alignUp(cursor, alignment);
            CompiledField.Kind kind;
            if (primitive != null) {
                kind = isArray ? CompiledField.Kind.PRIMITIVE_ARRAY : CompiledField.Kind.PRIMITIVE;
            } else {
                kind = isArray ? CompiledField.Kind.STRUCT_ARRAY : CompiledField.Kind.STRUCT;
            }

            Map<Long, String> enumValues = Map.of();
            JsonNode enumNode = f.get("enumValues");
            if (enumNode != null) {
                Map<Long, String> parsed = new LinkedHashMap<>();
                enumNode.properties().forEach(e -> parsed.put(Long.parseLong(e.getKey()), e.getValue().asText()));
                enumValues = Map.copyOf(parsed);
            }

            boolean stringLike = primitive == FieldType.CHAR && isArray
                    && !"array".equals(text(f, "presentation", "string"));

            fields.add(new CompiledField(fieldName, kind, primitive, nested, arrayLength, countField,
                    lengthConstant, offset, elementSize, alignment,
                    text(f, "unit", null), text(f, "doc", null), enumValues,
                    f.path("correlationId").asBoolean(false), stringLike));

            cursor = offset + elementSize * arrayLength;
            structAlign = Math.max(structAlign, alignment);
        }

        // A countField has to name a real sibling that can actually hold a count.
        for (CompiledField f : fields) {
            if (f.countField == null) {
                continue;
            }
            CompiledField counter = fields.stream()
                    .filter(c -> c.name.equals(f.countField)).findFirst()
                    .orElseThrow(() -> new SchemaException(name + "." + f.name
                            + ": countField '" + f.countField + "' is not a sibling field"));
            if (counter.kind != CompiledField.Kind.PRIMITIVE || counter.type.floating) {
                throw new SchemaException(name + "." + f.name + ": countField '" + f.countField
                        + "' must be a scalar integer field");
            }
        }

        int size = alignUp(cursor - baseOffset, structAlign);
        return new CompiledStruct(name, fields, size, structAlign);
    }

    static int alignUp(int value, int alignment) {
        int mask = alignment - 1;
        return (value + mask) & ~mask;
    }

    private static JsonNode require(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new SchemaException("missing required schema property '" + field + "' in " + node);
        }
        return value;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? fallback : value.asText();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
