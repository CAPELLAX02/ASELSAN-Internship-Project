package com.aselsan.dkm.gateway.library;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A saved message in the reusable library (FR-22..FR-24).
 *
 * <p>Both the raw bytes and the decoded payload are stored. The bytes are what
 * gets sent, so an entry stays byte-exact even if the schema later changes; the
 * payload is what gets shown, so an entry is still readable in the UI without
 * having to decode it against a schema that may no longer fit.
 *
 * <p>{@code schemaHash} is what makes FR-24 work: an entry saved against a
 * different interface is flagged stale on load rather than being sent with a
 * layout that is now wrong.
 */
public record LibraryEntry(String id,
                           String name,
                           String description,
                           List<String> tags,
                           String typeName,
                           long moduleId,
                           long msgId,
                           int length,
                           String schemaVersion,
                           String schemaHash,
                           long createdAt,
                           String bytesBase64,
                           JsonNode payload) {

    public boolean isStaleAgainst(String currentHash) {
        return schemaHash != null && !schemaHash.equals(currentHash);
    }
}
