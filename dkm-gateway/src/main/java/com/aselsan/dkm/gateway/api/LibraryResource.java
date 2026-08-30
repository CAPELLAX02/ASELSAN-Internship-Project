package com.aselsan.dkm.gateway.api;

import com.aselsan.dkm.gateway.library.LibraryEntry;
import com.aselsan.dkm.gateway.library.MessageLibrary;
import com.aselsan.dkm.gateway.model.MessageEntry;
import com.aselsan.dkm.gateway.playback.PlaybackEngine;
import com.aselsan.dkm.gateway.playback.PlaybackState;
import com.aselsan.dkm.gateway.schema.CompiledMessage;
import com.aselsan.dkm.gateway.schema.SchemaService;
import com.aselsan.dkm.gateway.session.SessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.Unpooled;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.List;

/** The reusable message library (FR-22..FR-24). */
@Path("/api/library")
@Produces(MediaType.APPLICATION_JSON)
public class LibraryResource {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Inject
    MessageLibrary library;

    @Inject
    SessionService session;

    @Inject
    SchemaService schemaService;

    @Inject
    MessageViews views;

    @Inject
    PlaybackEngine playback;

    @GET
    public ObjectNode list(@QueryParam("q") String query, @QueryParam("type") String type) {
        ObjectNode node = NODES.objectNode();
        node.put("available", library.isAvailable());
        node.put("reason", library.unavailableReason());
        node.put("directory", library.directory() == null ? null
                : library.directory().toAbsolutePath().toString());
        node.put("schemaHash", schemaService.model().hash());
        ArrayNode items = node.putArray("items");
        for (LibraryEntry entry : library.search(query, type)) {
            items.add(library.describe(entry));
        }
        return node;
    }

    /** Save an arbitrary payload straight into the library, without going through the session. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public ObjectNode save(JsonNode body) {
        String typeName = body.path("type").asText();
        CompiledMessage type = schemaService.model().message(typeName);
        if (type == null) {
            throw new IllegalArgumentException("unknown message type " + typeName);
        }
        byte[] bytes = session.build(type, body.path("timestamp").asLong(0), body.get("payload"));
        List<String> tags = new ArrayList<>();
        body.path("tags").forEach(tag -> tags.add(tag.asText()));
        return library.describe(library.save(body.path("name").asText(type.name),
                body.path("description").asText(""), tags, type, bytes));
    }

    @DELETE
    @Path("/{id}")
    public ObjectNode delete(@PathParam("id") String id) {
        ObjectNode node = NODES.objectNode();
        node.put("deleted", library.delete(id));
        return node;
    }

    /**
     * FR-23: drop a saved message into the current run.
     *
     * <p>Positioning and timing follow FR-9 exactly -- a library message does not
     * inherit timing from wherever it lands, it is given an explicit offset
     * relative to its new neighbour. A stale entry (saved against a different
     * interface) is refused unless the caller says to send it anyway.
     */
    @POST
    @Path("/{id}/insert")
    @Consumes(MediaType.APPLICATION_JSON)
    public ObjectNode insert(@PathParam("id") String id, JsonNode body) {
        if (playback.state() == PlaybackState.RUNNING) {
            throw new IllegalStateException("pause the run before inserting into it");
        }
        LibraryEntry entry = library.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("no library entry " + id);
        }
        boolean stale = entry.isStaleAgainst(schemaService.model().hash());
        if (stale && !body.path("force").asBoolean(false)) {
            throw new IllegalStateException("'" + entry.name() + "' was saved against interface "
                    + entry.schemaVersion() + " (" + entry.schemaHash() + ") but the current interface is "
                    + schemaService.model().version() + " (" + schemaService.model().hash()
                    + ") -- its byte layout may no longer be correct. Re-save it, or pass force=true.");
        }
        CompiledMessage type = schemaService.model().message(entry.typeName());
        if (type == null) {
            throw new IllegalArgumentException("the current interface has no type " + entry.typeName());
        }
        byte[] bytes = library.bytesOf(entry);
        JsonNode payload = schemaService.codec()
                .decode(type, Unpooled.wrappedBuffer(bytes), 0, bytes.length).get("payload");

        int index = body.path("index").asInt(session.read(() -> session.messages().size()));
        long offsetMillis = body.path("offsetMillis").asLong(0);
        MessageEntry inserted = session.insert(type.qualifiedName, index, offsetMillis, payload,
                com.aselsan.dkm.gateway.model.Origin.LIBRARY);
        ObjectNode result = session.read(() -> views.detail(session.messages(), inserted));
        result.put("insertedFromLibrary", entry.name());
        result.put("wasStale", stale);
        return result;
    }
}
