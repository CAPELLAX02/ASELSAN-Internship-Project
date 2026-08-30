package com.aselsan.dkm.gateway.schema;

import java.util.List;

/**
 * A message type with its full wire layout resolved.
 *
 * <p>{@link #fields} are the payload fields, at offsets absolute from the start
 * of the message (i.e. already past the header) -- so decoding never has to add
 * a base offset in the inner loop. The header itself is a separate
 * {@link CompiledStruct} shared by every message type.
 */
public final class CompiledMessage extends CompiledStruct {

    /** "RSP/DetectionReport" -- unique across the whole interface. */
    public final String qualifiedName;
    public final ModuleDef module;
    public final long msgId;
    public final Direction direction;
    public final CompiledStruct header;
    public final String doc;

    /**
     * Non-null when exactly one field is flagged {@code correlationId}. Drives
     * G9/FR-27 track rendering without the visualization config having to
     * restate it.
     */
    public final CompiledField correlationField;

    public CompiledMessage(ModuleDef module, String name, long msgId, Direction direction,
                           CompiledStruct header, List<CompiledField> payloadFields,
                           int size, int alignment, String doc) {
        super(name, payloadFields, size, alignment);
        this.qualifiedName = module.name() + "/" + name;
        this.module = module;
        this.msgId = msgId;
        this.direction = direction;
        this.header = header;
        this.doc = doc;

        CompiledField correlation = null;
        for (CompiledField f : payloadFields) {
            if (f.correlationId) {
                if (correlation != null) {
                    throw new SchemaException(qualifiedName + " declares more than one correlationId field");
                }
                correlation = f;
            }
        }
        this.correlationField = correlation;
    }

    public int headerSize() {
        return header.size;
    }

    @Override
    public String toString() {
        return qualifiedName + "#" + msgId + "{size=" + size + "}";
    }
}
