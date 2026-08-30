package com.aselsan.dkm.gateway.schema;

/** Schema is structurally wrong (bad type name, unresolvable constant, cycle). */
public class SchemaException extends RuntimeException {
    public SchemaException(String message) {
        super(message);
    }

    public SchemaException(String message, Throwable cause) {
        super(message, cause);
    }
}
