package com.aselsan.dkm.gateway.viz;

import com.aselsan.dkm.gateway.schema.CompiledMessage;
import com.aselsan.dkm.gateway.schema.FieldType;
import com.aselsan.dkm.gateway.schema.MessageCodec;
import io.netty.buffer.ByteBuf;

import java.util.Map;

/**
 * Turns one message's bytes into visualization samples with no allocation and no
 * intermediate decode.
 *
 * <p>Everything is a precomputed byte offset, so this runs on the receive path
 * itself: reading four doubles and pushing a 48-byte record costs far less than
 * handing the message to another thread would.
 *
 * <p><b>FR-26.</b> Polar-to-Cartesian conversion happens here, and it uses the
 * DKM's convention verbatim -- {@code x = distance * cos(heading)},
 * {@code y = distance * sin(heading)}, heading in radians, straight out of
 * {@code processing.cpp}. A visualization that used degrees, or a
 * navigation-style clockwise-from-north bearing, would draw a different
 * inside/outside verdict than the one the DKM actually applied, which is worse
 * than drawing nothing.
 */
public final class VizExtractor {

    public final CompiledMessage type;
    public final VizKind kind;
    public final boolean polar;
    public final int extraFlags;

    private final Map<String, VizBinding> bindings;

    /** Repeat support: one sample per live element of an array field. */
    private final int arrayOffset;
    private final int arrayStride;
    private final int arrayLength;
    private final int countOffset;
    private final FieldType countType;

    private final int correlationOffset;
    private final FieldType correlationType;

    public VizExtractor(CompiledMessage type, VizKind kind, boolean polar, int extraFlags,
                        Map<String, VizBinding> bindings,
                        int arrayOffset, int arrayStride, int arrayLength,
                        int countOffset, FieldType countType,
                        int correlationOffset, FieldType correlationType) {
        this.type = type;
        this.kind = kind;
        this.polar = polar;
        this.extraFlags = extraFlags;
        this.bindings = Map.copyOf(bindings);
        this.arrayOffset = arrayOffset;
        this.arrayStride = arrayStride;
        this.arrayLength = arrayLength;
        this.countOffset = countOffset;
        this.countType = countType;
        this.correlationOffset = correlationOffset;
        this.correlationType = correlationType;
    }

    public boolean repeats() {
        return arrayLength > 0;
    }

    /**
     * Emits every sample this message produces into {@code ring}.
     *
     * @return how many samples were emitted
     */
    public int extract(MessageCodec codec, ByteBuf buf, int base, int seq, int linkIndex,
                       boolean output, BeamHeadingIndex beams, VizRing ring) {
        if (kind == VizKind.NONE) {
            return 0;
        }
        double timestamp = codec.readNumeric(FieldType.USIZE, buf, base + codec.schema().timestampOffset());
        int flags = (output ? VizRing.FLAG_OUTPUT : 0) | extraFlags;
        int trackId = 0;
        if (correlationType != null) {
            trackId = (int) codec.readInteger(correlationType, buf, base + correlationOffset);
        }
        int msgId = (int) type.msgId;

        if (!repeats()) {
            return emit(codec, buf, base, base, seq, linkIndex, msgId, trackId, flags, timestamp, beams, ring)
                    ? 1 : 0;
        }

        int count = arrayLength;
        if (countType != null) {
            long declared = codec.readInteger(countType, buf, base + countOffset);
            count = (int) Math.max(0, Math.min(declared, arrayLength));
        }
        int emitted = 0;
        for (int i = 0; i < count; i++) {
            int elementBase = base + arrayOffset + i * arrayStride;
            if (emit(codec, buf, base, elementBase, seq, linkIndex, msgId, trackId, flags, timestamp, beams, ring)) {
                emitted++;
            }
        }
        return emitted;
    }

    private boolean emit(MessageCodec codec, ByteBuf buf, int base, int elementBase, int seq,
                         int linkIndex, int msgId, int trackId, int flags, double timestamp,
                         BeamHeadingIndex beams, VizRing ring) {
        float a;
        float b;
        float c;
        float d;
        float e = 0;
        float f = 0;

        switch (kind) {
            case POINT, TRACK -> {
                double x;
                double y;
                double distance;
                double heading;
                if (polar) {
                    distance = value("distance", codec, buf, base, elementBase, beams);
                    heading = value("heading", codec, buf, base, elementBase, beams);
                    if (Double.isNaN(distance) || Double.isNaN(heading)) {
                        return false;
                    }
                    // FR-26 -- mock_r/src/core/processing.cpp, verbatim.
                    x = distance * Math.cos(heading);
                    y = distance * Math.sin(heading);
                } else {
                    x = value("x", codec, buf, base, elementBase, beams);
                    y = value("y", codec, buf, base, elementBase, beams);
                    if (Double.isNaN(x) || Double.isNaN(y)) {
                        return false;
                    }
                    distance = Math.hypot(x, y);
                    heading = Math.atan2(y, x);
                }
                a = (float) x;
                b = (float) y;
                c = (float) distance;
                d = (float) heading;
                e = (float) value("vx", codec, buf, base, elementBase, beams);
                f = (float) value("vy", codec, buf, base, elementBase, beams);
                if (Float.isNaN(e)) {
                    e = 0;
                }
                if (Float.isNaN(f)) {
                    f = 0;
                }
            }
            case RAY -> {
                double heading = value("heading", codec, buf, base, elementBase, beams);
                if (Double.isNaN(heading)) {
                    return false; // unknown beam: draw nothing, same as the DKM knowing nothing
                }
                double length = value("length", codec, buf, base, elementBase, beams);
                if (Double.isNaN(length)) {
                    length = 0;
                }
                a = 0;
                b = 0;
                c = (float) length;
                d = (float) heading;
            }
            case LINE -> {
                a = (float) value("x", codec, buf, base, elementBase, beams);
                b = (float) value("y", codec, buf, base, elementBase, beams);
                c = 0;
                d = 0;
                e = (float) value("x2", codec, buf, base, elementBase, beams);
                f = (float) value("y2", codec, buf, base, elementBase, beams);
                if (Float.isNaN(a) || Float.isNaN(b) || Float.isNaN(e) || Float.isNaN(f)) {
                    return false;
                }
            }
            case CIRCULAR_AREA -> {
                a = (float) value("startDistance", codec, buf, base, elementBase, beams);
                b = (float) value("endDistance", codec, buf, base, elementBase, beams);
                c = (float) value("startHeading", codec, buf, base, elementBase, beams);
                d = (float) value("endHeading", codec, buf, base, elementBase, beams);
                if (Float.isNaN(a) || Float.isNaN(b) || Float.isNaN(c) || Float.isNaN(d)) {
                    return false;
                }
            }
            case RECT_AREA -> {
                a = (float) value("startX", codec, buf, base, elementBase, beams);
                b = (float) value("endX", codec, buf, base, elementBase, beams);
                c = (float) value("startY", codec, buf, base, elementBase, beams);
                d = (float) value("endY", codec, buf, base, elementBase, beams);
                if (Float.isNaN(a) || Float.isNaN(b) || Float.isNaN(c) || Float.isNaN(d)) {
                    return false;
                }
            }
            default -> {
                return false;
            }
        }
        return ring.offer(seq, msgId, linkIndex, kind, trackId, flags, a, b, c, d, timestamp, e, f);
    }

    private double value(String role, MessageCodec codec, ByteBuf buf, int base, int elementBase,
                         BeamHeadingIndex beams) {
        VizBinding binding = bindings.get(role);
        if (binding == null) {
            return Double.NaN;
        }
        if (binding.constant() != null) {
            return binding.constant();
        }
        if (binding.beamLookup()) {
            long key = codec.readInteger(binding.keyType(), buf, base + binding.keyOffset());
            return beams.get(key);
        }
        int at = (binding.inElement() ? elementBase : base) + binding.offset();
        return codec.readNumeric(binding.type(), buf, at);
    }
}
