package com.aselsan.dkm.gateway.schema;

import java.util.Locale;

/**
 * Primitive field types the schema can declare.
 *
 * <p>{@link #USIZE}/{@link #ISIZE} are the C++ {@code std::size_t} /
 * {@code std::ptrdiff_t} stand-ins: their width is not fixed by the schema but
 * by the target's data model ({@code dkm.wire.size-t-bytes}). The DKM currently
 * runs 64-bit, but a 32-bit VxWorks target would move every offset in every
 * message, and that has to be a config change rather than a schema rewrite.
 */
public enum FieldType {
    I8(1, true, false),
    U8(1, false, false),
    I16(2, true, false),
    U16(2, false, false),
    I32(4, true, false),
    U32(4, false, false),
    I64(8, true, false),
    U64(8, false, false),
    F32(4, true, true),
    F64(8, true, true),
    BOOL(1, false, false),
    CHAR(1, true, false),
    USIZE(-1, false, false),
    ISIZE(-1, true, false);

    /** Fixed width in bytes, or -1 when the width comes from the target data model. */
    public final int fixedWidth;
    public final boolean signed;
    public final boolean floating;

    FieldType(int fixedWidth, boolean signed, boolean floating) {
        this.fixedWidth = fixedWidth;
        this.signed = signed;
        this.floating = floating;
    }

    /** Effective width once the target's {@code sizeof(size_t)} is known. */
    public int width(int sizeTBytes) {
        return fixedWidth < 0 ? sizeTBytes : fixedWidth;
    }

    /**
     * Natural alignment. Matches every ABI this targets (x86-64 SysV, AArch64,
     * MSVC x64, PowerPC EABI): a scalar is aligned to its own size.
     */
    public int alignment(int sizeTBytes) {
        return width(sizeTBytes);
    }

    public static FieldType parse(String raw) {
        return switch (raw) {
            case "i8", "int8", "int8_t" -> I8;
            case "u8", "uint8", "uint8_t" -> U8;
            case "i16", "int16", "int16_t", "short" -> I16;
            case "u16", "uint16", "uint16_t", "unsigned short" -> U16;
            case "i32", "int32", "int32_t", "int" -> I32;
            case "u32", "uint32", "uint32_t", "unsigned", "unsigned int" -> U32;
            case "i64", "int64", "int64_t", "long long" -> I64;
            case "u64", "uint64", "uint64_t", "unsigned long long" -> U64;
            case "f32", "float" -> F32;
            case "f64", "double" -> F64;
            case "bool" -> BOOL;
            case "char" -> CHAR;
            case "usize", "size_t", "std::size_t" -> USIZE;
            case "isize", "ptrdiff_t", "std::ptrdiff_t" -> ISIZE;
            default -> null;
        };
    }

    /**
     * Lower-case wire name, as it appears in the schema JSON and the UI.
     *
     * <p>{@code Locale.ROOT} is load-bearing. Under a Turkish locale the default
     * {@code toLowerCase()} maps USIZE to "us\u0131ze" (dotless i), and the
     * schema would then advertise a type name nothing can parse back. Type names
     * are protocol identifiers, so they are always folded in the root locale --
     * as is every other case conversion in this service.
     */
    public String schemaName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
