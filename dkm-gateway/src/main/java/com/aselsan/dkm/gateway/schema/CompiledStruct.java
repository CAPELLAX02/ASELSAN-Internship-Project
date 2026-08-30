package com.aselsan.dkm.gateway.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A struct whose C++ memory layout has been resolved: every field carries a
 * byte offset, and {@link #size}/{@link #alignment} follow the standard-layout
 * rules the DKM's compiler applies (each member at the next offset that
 * satisfies its natural alignment, trailing padding out to the struct's own
 * alignment).
 */
public class CompiledStruct {

    public final String name;
    public final List<CompiledField> fields;
    public final int size;
    public final int alignment;

    private final Map<String, CompiledField> byName;

    public CompiledStruct(String name, List<CompiledField> fields, int size, int alignment) {
        this.name = name;
        this.fields = List.copyOf(fields);
        this.size = size;
        this.alignment = alignment;
        Map<String, CompiledField> index = new LinkedHashMap<>(fields.size() * 2);
        for (CompiledField f : this.fields) {
            index.put(f.name, f);
        }
        this.byName = Map.copyOf(index);
    }

    public CompiledField field(String fieldName) {
        return byName.get(fieldName);
    }

    public CompiledField requireField(String fieldName) {
        CompiledField f = byName.get(fieldName);
        if (f == null) {
            throw new SchemaException(name + " has no field '" + fieldName + "'");
        }
        return f;
    }

    @Override
    public String toString() {
        return name + "{size=" + size + ",align=" + alignment + "}";
    }
}
