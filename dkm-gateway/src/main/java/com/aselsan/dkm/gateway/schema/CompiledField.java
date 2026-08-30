package com.aselsan.dkm.gateway.schema;

import java.util.Map;

/**
 * One field of a compiled struct, with its byte offset already resolved
 * relative to the start of the owning struct.
 *
 * <p>Nothing here is looked up by name at decode time -- the whole point of
 * compiling the schema once at startup is that the hot path only ever touches
 * an int offset and a switch on {@link #type}.
 */
public final class CompiledField {

    public enum Kind { PRIMITIVE, STRUCT, PRIMITIVE_ARRAY, STRUCT_ARRAY }

    public final String name;
    public final Kind kind;
    /** Non-null for PRIMITIVE and PRIMITIVE_ARRAY. */
    public final FieldType type;
    /** Non-null for STRUCT and STRUCT_ARRAY. */
    public final CompiledStruct struct;

    /** Declared element count; 1 for non-array fields. */
    public final int arrayLength;
    /** Name of the sibling field holding the live element count, or null. */
    public final String countField;
    /** Name of the C++ constant the array length came from, for regeneration. */
    public final String lengthConstant;

    public final int offset;
    public final int elementSize;
    public final int size;
    public final int alignment;

    public final String unit;
    public final String doc;
    public final Map<Long, String> enumValues;
    public final boolean correlationId;
    /** Render a {@code char} array as text rather than a list of numbers. */
    public final boolean stringLike;

    CompiledField(String name, Kind kind, FieldType type, CompiledStruct struct,
                  int arrayLength, String countField, String lengthConstant,
                  int offset, int elementSize, int alignment,
                  String unit, String doc, Map<Long, String> enumValues,
                  boolean correlationId, boolean stringLike) {
        this.name = name;
        this.kind = kind;
        this.type = type;
        this.struct = struct;
        this.arrayLength = arrayLength;
        this.countField = countField;
        this.lengthConstant = lengthConstant;
        this.offset = offset;
        this.elementSize = elementSize;
        this.size = elementSize * arrayLength;
        this.alignment = alignment;
        this.unit = unit;
        this.doc = doc;
        this.enumValues = enumValues;
        this.correlationId = correlationId;
        this.stringLike = stringLike;
    }

    public boolean isArray() {
        return kind == Kind.PRIMITIVE_ARRAY || kind == Kind.STRUCT_ARRAY;
    }

    public boolean isStructLike() {
        return kind == Kind.STRUCT || kind == Kind.STRUCT_ARRAY;
    }

    /** Absolute offset of array element {@code i}. */
    public int elementOffset(int i) {
        return offset + i * elementSize;
    }

    @Override
    public String toString() {
        return name + "@" + offset + "+" + size;
    }
}
