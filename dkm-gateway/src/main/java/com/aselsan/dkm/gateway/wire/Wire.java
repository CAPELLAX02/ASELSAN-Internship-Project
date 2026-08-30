package com.aselsan.dkm.gateway.wire;

import io.netty.buffer.ByteBuf;

/**
 * Endian-aware primitive access over a {@link ByteBuf}.
 *
 * <p>One instance is built at startup from {@code dkm.wire.byte-order} and kept
 * for the process lifetime, so the {@code littleEndian} branch below is
 * perfectly predicted and effectively free. The DKM writes structs straight to
 * the socket with no serialization step, so "the wire format" is literally the
 * target's memory layout -- which means byte order is a property of the target
 * CPU, not of the protocol. x86-64 and AArch64 are little-endian; a PowerPC
 * VxWorks target would not be, and that has to be a config flip rather than a
 * rewrite.
 */
public final class Wire {

    private final boolean littleEndian;

    public Wire(boolean littleEndian) {
        this.littleEndian = littleEndian;
    }

    public boolean littleEndian() {
        return littleEndian;
    }

    // ---- reads ---------------------------------------------------------

    public long readUnsigned(ByteBuf buf, int index, int width) {
        return switch (width) {
            case 1 -> buf.getUnsignedByte(index);
            case 2 -> littleEndian ? buf.getUnsignedShortLE(index) : buf.getUnsignedShort(index);
            case 4 -> littleEndian ? buf.getUnsignedIntLE(index) : buf.getUnsignedInt(index);
            case 8 -> littleEndian ? buf.getLongLE(index) : buf.getLong(index);
            default -> throw new IllegalArgumentException("unsupported width " + width);
        };
    }

    public long readSigned(ByteBuf buf, int index, int width) {
        return switch (width) {
            case 1 -> buf.getByte(index);
            case 2 -> littleEndian ? buf.getShortLE(index) : buf.getShort(index);
            case 4 -> littleEndian ? buf.getIntLE(index) : buf.getInt(index);
            case 8 -> littleEndian ? buf.getLongLE(index) : buf.getLong(index);
            default -> throw new IllegalArgumentException("unsupported width " + width);
        };
    }

    public float readF32(ByteBuf buf, int index) {
        return Float.intBitsToFloat(littleEndian ? buf.getIntLE(index) : buf.getInt(index));
    }

    public double readF64(ByteBuf buf, int index) {
        return Double.longBitsToDouble(littleEndian ? buf.getLongLE(index) : buf.getLong(index));
    }

    // ---- writes --------------------------------------------------------

    public void writeInteger(ByteBuf buf, int index, int width, long value) {
        switch (width) {
            case 1 -> buf.setByte(index, (int) value);
            case 2 -> {
                if (littleEndian) buf.setShortLE(index, (int) value);
                else buf.setShort(index, (int) value);
            }
            case 4 -> {
                if (littleEndian) buf.setIntLE(index, (int) value);
                else buf.setInt(index, (int) value);
            }
            case 8 -> {
                if (littleEndian) buf.setLongLE(index, value);
                else buf.setLong(index, value);
            }
            default -> throw new IllegalArgumentException("unsupported width " + width);
        }
    }

    public void writeF32(ByteBuf buf, int index, float value) {
        int bits = Float.floatToRawIntBits(value);
        if (littleEndian) buf.setIntLE(index, bits);
        else buf.setInt(index, bits);
    }

    public void writeF64(ByteBuf buf, int index, double value) {
        long bits = Double.doubleToRawLongBits(value);
        if (littleEndian) buf.setLongLE(index, bits);
        else buf.setLong(index, bits);
    }
}
