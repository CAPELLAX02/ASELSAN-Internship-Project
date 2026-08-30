package com.aselsan.dkm.gateway.schema;

import com.aselsan.dkm.gateway.wire.Wire;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBuf;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Decode / encode / validate against a compiled schema (FR-2, FR-3, FR-5).
 *
 * <p>Two properties this deliberately guarantees:
 * <ul>
 *   <li><b>Byte-exact round trip (NFR-4).</b> Encoding writes <em>over</em> the
 *       message's existing bytes rather than building a fresh buffer, so any
 *       byte the schema doesn't describe -- ABI padding, a field added to the
 *       target's header but not yet to the schema -- survives an edit untouched.
 *       A re-saved binary stays valid for the existing downstream tools.</li>
 *   <li><b>No reflection, no per-field name lookup.</b> Everything is an int
 *       offset resolved once by {@link SchemaCompiler}.</li>
 * </ul>
 */
public final class MessageCodec {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final SchemaModel schema;
    private final Wire wire;

    public MessageCodec(SchemaModel schema, Wire wire) {
        this.schema = schema;
        this.wire = wire;
    }

    public SchemaModel schema() {
        return schema;
    }

    public Wire wire() {
        return wire;
    }

    // ---- decode --------------------------------------------------------

    /**
     * Decodes a whole message. {@code base} is the index of its first header
     * byte inside {@code buf}.
     */
    public ObjectNode decode(CompiledMessage type, ByteBuf buf, int base, int length) {
        if (length < type.size) {
            throw new CodecException(type.qualifiedName + ": message is " + length
                    + " bytes but the schema says " + type.size
                    + " -- the capture predates the current interface, or the schema is out of date");
        }
        ObjectNode root = NODES.objectNode();
        root.set("header", decodeStruct(type.header.fields, buf, base));
        root.set("payload", decodeStruct(type.fields, buf, base));
        return root;
    }

    /** Header-only decode, for a message whose msg_id the schema doesn't know. */
    public ObjectNode decodeHeader(ByteBuf buf, int base) {
        return decodeStruct(schema.header().fields, buf, base);
    }

    private ObjectNode decodeStruct(List<CompiledField> fields, ByteBuf buf, int base) {
        ObjectNode node = NODES.objectNode();
        for (CompiledField f : fields) {
            node.set(f.name, decodeField(f, buf, base));
        }
        return node;
    }

    private JsonNode decodeField(CompiledField f, ByteBuf buf, int base) {
        switch (f.kind) {
            case PRIMITIVE:
                return decodePrimitive(f.type, buf, base + f.offset);
            case STRUCT:
                return decodeStructAt(f.struct, buf, base + f.offset);
            case PRIMITIVE_ARRAY: {
                if (f.stringLike) {
                    byte[] raw = new byte[f.arrayLength];
                    buf.getBytes(base + f.offset, raw);
                    int end = 0;
                    while (end < raw.length && raw[end] != 0) {
                        end++;
                    }
                    return NODES.textNode(new String(raw, 0, end, StandardCharsets.US_ASCII));
                }
                ArrayNode array = NODES.arrayNode(f.arrayLength);
                for (int i = 0; i < f.arrayLength; i++) {
                    array.add(decodePrimitive(f.type, buf, base + f.elementOffset(i)));
                }
                return array;
            }
            case STRUCT_ARRAY: {
                ArrayNode array = NODES.arrayNode(f.arrayLength);
                for (int i = 0; i < f.arrayLength; i++) {
                    array.add(decodeStructAt(f.struct, buf, base + f.elementOffset(i)));
                }
                return array;
            }
            default:
                throw new IllegalStateException("unreachable kind " + f.kind);
        }
    }

    private ObjectNode decodeStructAt(CompiledStruct struct, ByteBuf buf, int absoluteOffset) {
        ObjectNode node = NODES.objectNode();
        for (CompiledField f : struct.fields) {
            node.set(f.name, decodeField(f, buf, absoluteOffset));
        }
        return node;
    }

    private JsonNode decodePrimitive(FieldType type, ByteBuf buf, int index) {
        int width = type.width(schema.sizeTBytes());
        if (type.floating) {
            return type == FieldType.F32
                    ? NODES.numberNode(wire.readF32(buf, index))
                    : NODES.numberNode(wire.readF64(buf, index));
        }
        if (type == FieldType.BOOL) {
            return NODES.booleanNode(buf.getByte(index) != 0);
        }
        if (type.signed) {
            return NODES.numberNode(wire.readSigned(buf, index, width));
        }
        long value = wire.readUnsigned(buf, index, width);
        if (width == 8 && value < 0) {
            // Beyond Long.MAX_VALUE: keep it exact rather than wrapping negative.
            return NODES.numberNode(new BigInteger(Long.toUnsignedString(value)));
        }
        return NODES.numberNode(value);
    }

    // ---- fast scalar reads (visualization hot path) --------------------

    /** Reads one numeric field as a double with no allocation at all. */
    public double readNumeric(FieldType type, ByteBuf buf, int index) {
        int width = type.width(schema.sizeTBytes());
        if (type == FieldType.F64) {
            return wire.readF64(buf, index);
        }
        if (type == FieldType.F32) {
            return wire.readF32(buf, index);
        }
        if (type.signed) {
            return wire.readSigned(buf, index, width);
        }
        long v = wire.readUnsigned(buf, index, width);
        return width == 8 && v < 0 ? Math.scalb((double) (v >>> 1), 1) : (double) v;
    }

    public long readInteger(FieldType type, ByteBuf buf, int index) {
        int width = type.width(schema.sizeTBytes());
        return type.signed ? wire.readSigned(buf, index, width) : wire.readUnsigned(buf, index, width);
    }

    // ---- encode --------------------------------------------------------

    /**
     * Writes {@code payload} (and optionally {@code header}) over the bytes
     * already at {@code base}. The caller is responsible for having those bytes
     * be either the original message (an edit) or zeros (a new message).
     */
    public void encode(CompiledMessage type, JsonNode message, ByteBuf dst, int base) {
        List<String> issues = new ArrayList<>();
        JsonNode header = message.get("header");
        if (header != null && header.isObject()) {
            encodeStruct(type.header.fields, header, dst, base, "header", issues);
        }
        JsonNode payload = message.get("payload");
        if (payload != null && payload.isObject()) {
            encodeStruct(type.fields, payload, dst, base, "payload", issues);
        }
        if (!issues.isEmpty()) {
            throw new CodecException(type.qualifiedName + ": " + issues.size() + " invalid field(s)", issues);
        }
    }

    /**
     * Validates without writing anything (FR-5). Returns the list of problems;
     * empty means the message is safe to send or save.
     */
    public List<String> validate(CompiledMessage type, JsonNode message) {
        List<String> issues = new ArrayList<>();
        JsonNode payload = message.get("payload");
        if (payload == null || !payload.isObject()) {
            issues.add("payload: missing");
            return issues;
        }
        validateStruct(type.fields, payload, "payload", issues);
        return issues;
    }

    private void validateStruct(List<CompiledField> fields, JsonNode node, String path, List<String> issues) {
        for (CompiledField f : fields) {
            JsonNode value = node.get(f.name);
            String fieldPath = path + "." + f.name;
            if (value == null || value.isNull()) {
                continue; // absent means "leave the existing bytes alone"
            }
            checkField(f, value, fieldPath, node, issues);
        }
    }

    private void checkField(CompiledField f, JsonNode value, String fieldPath, JsonNode parent, List<String> issues) {
        switch (f.kind) {
            case PRIMITIVE -> checkPrimitive(f.type, value, fieldPath, issues);
            case STRUCT -> {
                if (!value.isObject()) {
                    issues.add(fieldPath + ": expected an object");
                } else {
                    validateStruct(f.struct.fields, value, fieldPath, issues);
                }
            }
            case PRIMITIVE_ARRAY, STRUCT_ARRAY -> {
                if (f.stringLike) {
                    if (!value.isTextual()) {
                        issues.add(fieldPath + ": expected a string");
                    } else if (value.asText().length() >= f.arrayLength) {
                        issues.add(fieldPath + ": " + value.asText().length() + " characters exceeds the "
                                + (f.arrayLength - 1) + " that fit alongside a terminator");
                    }
                    return;
                }
                if (!value.isArray()) {
                    issues.add(fieldPath + ": expected an array");
                    return;
                }
                if (value.size() > f.arrayLength) {
                    issues.add(fieldPath + ": " + value.size() + " elements exceeds the declared capacity of "
                            + f.arrayLength);
                }
                // FR-5: the count field and the live array length must agree.
                if (f.countField != null && parent != null) {
                    JsonNode countNode = parent.get(f.countField);
                    if (countNode != null && countNode.isNumber()) {
                        long declared = countNode.asLong();
                        if (declared > f.arrayLength) {
                            issues.add(fieldPath + ": " + f.countField + " = " + declared
                                    + " exceeds the declared capacity of " + f.arrayLength);
                        }
                        if (declared > value.size()) {
                            issues.add(fieldPath + ": " + f.countField + " = " + declared
                                    + " but only " + value.size() + " element(s) were supplied");
                        }
                    }
                }
                for (int i = 0; i < Math.min(value.size(), f.arrayLength); i++) {
                    JsonNode element = value.get(i);
                    String elementPath = fieldPath + "[" + i + "]";
                    if (f.kind == CompiledField.Kind.PRIMITIVE_ARRAY) {
                        checkPrimitive(f.type, element, elementPath, issues);
                    } else if (!element.isObject()) {
                        issues.add(elementPath + ": expected an object");
                    } else {
                        validateStruct(f.struct.fields, element, elementPath, issues);
                    }
                }
            }
        }
    }

    private void checkPrimitive(FieldType type, JsonNode value, String path, List<String> issues) {
        if (type == FieldType.BOOL) {
            if (!value.isBoolean() && !value.isNumber()) {
                issues.add(path + ": expected true/false");
            }
            return;
        }
        if (!value.isNumber()) {
            issues.add(path + ": expected a number, got " + value.getNodeType().toString().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        if (type.floating) {
            double d = value.asDouble();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                issues.add(path + ": " + d + " is not a finite value");
            } else if (type == FieldType.F32 && Math.abs(d) > Float.MAX_VALUE) {
                issues.add(path + ": " + d + " overflows a 32-bit float");
            }
            return;
        }
        if (!value.canConvertToLong() && !value.isBigInteger()) {
            issues.add(path + ": " + value.asText() + " is not a whole number");
            return;
        }
        int width = type.width(schema.sizeTBytes());
        BigInteger v = value.isBigInteger() ? value.bigIntegerValue() : BigInteger.valueOf(value.asLong());
        BigInteger min = type.signed ? BigInteger.ONE.shiftLeft(width * 8 - 1).negate() : BigInteger.ZERO;
        BigInteger max = type.signed
                ? BigInteger.ONE.shiftLeft(width * 8 - 1).subtract(BigInteger.ONE)
                : BigInteger.ONE.shiftLeft(width * 8).subtract(BigInteger.ONE);
        if (v.compareTo(min) < 0 || v.compareTo(max) > 0) {
            issues.add(path + ": " + v + " is outside the range of " + type.schemaName()
                    + " [" + min + ", " + max + "]");
        }
    }

    private void encodeStruct(List<CompiledField> fields, JsonNode node, ByteBuf dst, int base,
                              String path, List<String> issues) {
        for (CompiledField f : fields) {
            JsonNode value = node.get(f.name);
            if (value == null || value.isNull()) {
                continue;
            }
            String fieldPath = path + "." + f.name;
            checkField(f, value, fieldPath, node, issues);
            if (!issues.isEmpty()) {
                continue;
            }
            encodeField(f, value, dst, base + f.offset, base, issues, fieldPath);
        }
    }

    private void encodeField(CompiledField f, JsonNode value, ByteBuf dst, int absolute,
                             int base, List<String> issues, String path) {
        switch (f.kind) {
            case PRIMITIVE -> encodePrimitive(f.type, value, dst, absolute);
            case STRUCT -> encodeNested(f.struct, value, dst, absolute, issues, path);
            case PRIMITIVE_ARRAY -> {
                if (f.stringLike) {
                    byte[] raw = new byte[f.arrayLength];
                    byte[] text = value.asText().getBytes(StandardCharsets.US_ASCII);
                    System.arraycopy(text, 0, raw, 0, Math.min(text.length, f.arrayLength - 1));
                    dst.setBytes(absolute, raw);
                    return;
                }
                int n = Math.min(value.size(), f.arrayLength);
                for (int i = 0; i < n; i++) {
                    encodePrimitive(f.type, value.get(i), dst, base + f.elementOffset(i));
                }
            }
            case STRUCT_ARRAY -> {
                int n = Math.min(value.size(), f.arrayLength);
                for (int i = 0; i < n; i++) {
                    encodeNested(f.struct, value.get(i), dst, base + f.elementOffset(i), issues,
                            path + "[" + i + "]");
                }
            }
        }
    }

    private void encodeNested(CompiledStruct struct, JsonNode node, ByteBuf dst, int absoluteOffset,
                              List<String> issues, String path) {
        for (CompiledField f : struct.fields) {
            JsonNode value = node.get(f.name);
            if (value == null || value.isNull()) {
                continue;
            }
            encodeField(f, value, dst, absoluteOffset + f.offset, absoluteOffset, issues,
                    path + "." + f.name);
        }
    }

    private void encodePrimitive(FieldType type, JsonNode value, ByteBuf dst, int index) {
        int width = type.width(schema.sizeTBytes());
        if (type == FieldType.F64) {
            wire.writeF64(dst, index, value.asDouble());
        } else if (type == FieldType.F32) {
            wire.writeF32(dst, index, (float) value.asDouble());
        } else if (type == FieldType.BOOL) {
            dst.setByte(index, (value.isBoolean() ? value.asBoolean() : value.asLong() != 0) ? 1 : 0);
        } else if (value.isBigInteger()) {
            wire.writeInteger(dst, index, width, value.bigIntegerValue().longValue());
        } else {
            wire.writeInteger(dst, index, width, value.asLong());
        }
    }

    // ---- header helpers -------------------------------------------------

    public long senderId(ByteBuf buf, int base) {
        return wire.readUnsigned(buf, base + schema.senderIdOffset(), schema.sizeTBytes());
    }

    public long receiverId(ByteBuf buf, int base) {
        return wire.readUnsigned(buf, base + schema.receiverIdOffset(), schema.sizeTBytes());
    }

    public long msgId(ByteBuf buf, int base) {
        return wire.readUnsigned(buf, base + schema.msgIdOffset(), schema.sizeTBytes());
    }

    public long timestamp(ByteBuf buf, int base) {
        return wire.readUnsigned(buf, base + schema.timestampOffset(), schema.sizeTBytes());
    }

    public long msgLength(ByteBuf buf, int base) {
        return wire.readUnsigned(buf, base + schema.msgLengthOffset(), schema.sizeTBytes());
    }

    public void writeHeader(ByteBuf dst, int base, long senderId, long receiverId,
                            long msgId, long timestamp, long msgLength) {
        int w = schema.sizeTBytes();
        wire.writeInteger(dst, base + schema.senderIdOffset(), w, senderId);
        wire.writeInteger(dst, base + schema.receiverIdOffset(), w, receiverId);
        wire.writeInteger(dst, base + schema.msgIdOffset(), w, msgId);
        wire.writeInteger(dst, base + schema.timestampOffset(), w, timestamp);
        wire.writeInteger(dst, base + schema.msgLengthOffset(), w, msgLength);
    }
}
