package com.aselsan.dkm.gateway.api;

import com.aselsan.dkm.gateway.library.LibraryEntry;
import com.aselsan.dkm.gateway.library.MessageLibrary;
import com.aselsan.dkm.gateway.model.MessageEntry;
import com.aselsan.dkm.gateway.model.MessageSet;
import com.aselsan.dkm.gateway.playback.PlaybackEngine;
import com.aselsan.dkm.gateway.playback.PlaybackState;
import com.aselsan.dkm.gateway.schema.CompiledMessage;
import com.aselsan.dkm.gateway.schema.ModuleDef;
import com.aselsan.dkm.gateway.schema.SchemaService;
import com.aselsan.dkm.gateway.session.SessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The editable stimulus set: load a binary, browse it, edit it, extend it, save
 * it back out (FR-6..FR-10).
 */
@Path("/api/session")
@Produces(MediaType.APPLICATION_JSON)
public class SessionResource {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Inject
    SessionService session;

    @Inject
    SchemaService schemaService;

    @Inject
    MessageViews views;

    @Inject
    PlaybackEngine playback;

    @Inject
    MessageLibrary library;

    // ---- listing ---------------------------------------------------------

    @GET
    @Path("/messages")
    public ObjectNode list(@QueryParam("link") String link,
                           @QueryParam("type") String type,
                           @QueryParam("status") String status,
                           @QueryParam("sort") String sort,
                           @QueryParam("dir") @DefaultValue("asc") String dir,
                           @QueryParam("offset") @DefaultValue("0") int offset,
                           @QueryParam("limit") @DefaultValue("200") int limit) {
        int capped = Math.min(Math.max(limit, 1), 1000);
        MessageSort order = MessageSort.parse(sort);
        boolean descending = "desc".equalsIgnoreCase(dir);
        return session.read(() -> {
            MessageSet set = session.messages();
            List<MessageEntry> filtered = new ArrayList<>();
            for (MessageEntry entry : set.entries()) {
                if (matches(entry, link, type, status)) {
                    filtered.add(entry);
                }
            }
            order.apply(filtered, descending);

            ObjectNode node = NODES.objectNode();
            node.put("source", set.sourceName());
            node.put("sort", order.name().toLowerCase(java.util.Locale.ROOT));
            node.put("dir", descending ? "desc" : "asc");
            node.put("total", set.size());
            node.put("filtered", filtered.size());
            node.put("offset", offset);
            node.put("limit", capped);
            ArrayNode items = node.putArray("items");
            for (int i = offset; i < Math.min(filtered.size(), offset + capped); i++) {
                items.add(views.summary(set, filtered.get(i)));
            }
            return node;
        });
    }

    @GET
    @Path("/messages/{id}")
    public ObjectNode detail(@PathParam("id") long id) {
        return session.read(() -> {
            MessageEntry entry = require(id);
            return views.detail(session.messages(), entry);
        });
    }

    private boolean matches(MessageEntry entry, String link, String type, String status) {
        if (link != null && !link.isBlank()) {
            ModuleDef module = schemaService.model().moduleById(entry.moduleId);
            if (module == null || !module.name().equalsIgnoreCase(link)) {
                return false;
            }
        }
        if (type != null && !type.isBlank() && !type.equals(entry.typeName)) {
            return false;
        }
        if (status != null && !status.isBlank()) {
            return switch (status.toLowerCase(java.util.Locale.ROOT)) {
                case "sent" -> entry.sent;
                case "pending" -> !entry.sent;
                case "problem" -> entry.problem != null;
                default -> true;
            };
        }
        return true;
    }

    private MessageEntry require(long id) {
        MessageEntry entry = session.messages().byId(id);
        if (entry == null) {
            throw new IllegalArgumentException("no message with id " + id);
        }
        return entry;
    }

    // ---- editing ---------------------------------------------------------

    /**
     * FR-8. Edits are refused while the pacer is running: a message that is
     * about to go out must not change under it, and the requirement only asks
     * for editing during a paused run.
     */
    @PUT
    @Path("/messages/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public ObjectNode edit(@PathParam("id") long id, JsonNode body) {
        requireNotRunning();
        MessageEntry entry = session.edit(id, body);
        return session.read(() -> views.detail(session.messages(), entry));
    }

    @PUT
    @Path("/messages/{id}/timestamp")
    @Consumes(MediaType.APPLICATION_JSON)
    public ObjectNode retime(@PathParam("id") long id, JsonNode body) {
        requireNotRunning();
        MessageEntry entry = session.retime(id, body.path("timestamp").asLong());
        return session.read(() -> views.detail(session.messages(), entry));
    }

    /** FR-9: a new message goes in at a chosen position with an explicit timing offset. */
    @POST
    @Path("/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    public ObjectNode insert(JsonNode body) {
        requireNotRunning();
        String type = body.path("type").asText();
        int index = body.path("index").asInt(session.read(() -> session.messages().size()));
        long offsetMillis = body.path("offsetMillis").asLong(0);
        MessageEntry entry = session.insert(type, index, offsetMillis, body.get("payload"));
        return session.read(() -> views.detail(session.messages(), entry));
    }

    @DELETE
    @Path("/messages/{id}")
    public ObjectNode delete(@PathParam("id") long id) {
        requireNotRunning();
        boolean removed = session.delete(id);
        ObjectNode node = NODES.objectNode();
        node.put("deleted", removed);
        node.put("total", session.read(() -> session.messages().size()));
        return node;
    }

    /** FR-22: save the message as currently edited into the reusable library. */
    @POST
    @Path("/messages/{id}/library")
    @Consumes(MediaType.APPLICATION_JSON)
    public ObjectNode saveToLibrary(@PathParam("id") long id, JsonNode body) {
        record Saved(byte[] bytes, MessageEntry entry) {
        }
        Saved saved = session.read(() -> {
            MessageEntry entry = require(id);
            return new Saved(session.messages().bytesOf(entry), entry);
        });
        List<String> tags = new ArrayList<>();
        body.path("tags").forEach(tag -> tags.add(tag.asText()));
        LibraryEntry stored = library.saveFrom(saved.entry(), saved.bytes(),
                body.path("name").asText(saved.entry().typeName + " " + saved.entry().id),
                body.path("description").asText(""), tags);
        return library.describe(stored);
    }

    private void requireNotRunning() {
        if (playback.state() == PlaybackState.RUNNING) {
            throw new IllegalStateException("the run is playing -- pause it first. "
                    + "Pending messages are editable while paused (FR-8), and resuming re-derives"
                    + " their send times from the edited timeline (FR-14).");
        }
    }

    // ---- files -----------------------------------------------------------

    /** FR-6: load an input binary through the browser. */
    @POST
    @Path("/load")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public ObjectNode upload(@RestForm("file") FileUpload file) throws IOException {
        requireNotRunning();
        try (InputStream in = Files.newInputStream(file.uploadedFile())) {
            MessageSet.ParseResult result = session.load(in, file.fileName(), file.size());
            return describe(result);
        }
    }

    /** Same, for a file already on the machine running the gateway -- the fast path for large captures. */
    @POST
    @Path("/load-path")
    @Consumes(MediaType.APPLICATION_JSON)
    public ObjectNode loadPath(JsonNode body) throws IOException {
        requireNotRunning();
        java.nio.file.Path path = java.nio.file.Path.of(body.path("path").asText());
        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException("cannot read " + path.toAbsolutePath());
        }
        return describe(session.loadFile(path));
    }

    private ObjectNode describe(MessageSet.ParseResult result) {
        ObjectNode node = NODES.objectNode();
        node.put("messages", result.messages());
        node.put("bytes", result.bytesConsumed());
        node.put("malformed", result.malformed());
        ArrayNode notes = node.putArray("notes");
        result.notes().forEach(notes::add);
        ArrayNode problems = node.putArray("problems");
        session.problems().forEach(problems::add);
        return node;
    }

    /** FR-10: write the edited set back out in the original binary format. */
    @GET
    @Path("/export")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response export() {
        StreamingOutput stream = out -> session.save(out);
        return Response.ok(stream)
                .header("Content-Disposition", "attachment; filename=\"input.bin\"")
                .build();
    }

    @POST
    @Path("/clear")
    public ObjectNode clear() {
        requireNotRunning();
        session.clear();
        ObjectNode node = NODES.objectNode();
        node.put("total", 0);
        return node;
    }

    /**
     * Template payload for a brand-new message of a given type, all fields
     * zeroed. Passed as a query parameter rather than a path segment because a
     * qualified type name contains a slash ("RSP/DetectionReport").
     */
    @GET
    @Path("/template")
    public ObjectNode template(@QueryParam("type") String qualifiedName) {
        CompiledMessage type = schemaService.model().message(qualifiedName);
        if (type == null) {
            throw new IllegalArgumentException("unknown message type " + qualifiedName);
        }
        byte[] zeroed = session.build(type, 0, null);
        return schemaService.codec().decode(type, io.netty.buffer.Unpooled.wrappedBuffer(zeroed), 0, zeroed.length);
    }
}
