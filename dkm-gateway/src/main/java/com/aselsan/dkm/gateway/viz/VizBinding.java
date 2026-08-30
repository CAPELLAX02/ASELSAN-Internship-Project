package com.aselsan.dkm.gateway.viz;

import com.aselsan.dkm.gateway.schema.FieldType;

/**
 * One resolved binding from a visualization role (x, heading, startDistance, ...)
 * to somewhere a number can be obtained.
 *
 * @param offset      byte offset, absolute from message start, or from the
 *                    repeat element when {@code inElement}
 * @param type        primitive type at that offset
 * @param inElement   true when the offset is relative to a repeat element
 * @param constant    fixed value instead of a field read, or null
 * @param beamLookup  resolve via {@link BeamHeadingIndex} keyed on {@code keyOffset}
 * @param keyOffset   offset of the id field used for the lookup
 * @param keyType     type of that id field
 */
public record VizBinding(int offset, FieldType type, boolean inElement, Double constant,
                         boolean beamLookup, int keyOffset, FieldType keyType) {

    public static VizBinding field(int offset, FieldType type, boolean inElement) {
        return new VizBinding(offset, type, inElement, null, false, -1, null);
    }

    public static VizBinding constant(double value) {
        return new VizBinding(-1, null, false, value, false, -1, null);
    }

    public static VizBinding beamHeading(int keyOffset, FieldType keyType) {
        return new VizBinding(-1, null, false, null, true, keyOffset, keyType);
    }
}
