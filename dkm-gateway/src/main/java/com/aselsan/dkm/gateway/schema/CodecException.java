package com.aselsan.dkm.gateway.schema;

import java.util.List;

/** A message could not be decoded or encoded against the schema. */
public class CodecException extends RuntimeException {

    private final List<String> issues;

    public CodecException(String message) {
        super(message);
        this.issues = List.of(message);
    }

    public CodecException(String message, List<String> issues) {
        super(message + (issues.isEmpty() ? "" : " -- " + String.join("; ", issues)));
        this.issues = List.copyOf(issues);
    }

    public List<String> issues() {
        return issues;
    }
}
