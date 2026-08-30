package com.aselsan.dkm.gateway.api;

import com.aselsan.dkm.gateway.capture.CaptureService;
import com.aselsan.dkm.gateway.model.MessageEntry;
import com.aselsan.dkm.gateway.model.MessageSet;
import com.aselsan.dkm.gateway.schema.ModuleDef;
import com.aselsan.dkm.gateway.schema.SchemaService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.util.ArrayList;
import java.util.List;

/**
 * Captured DKM output (FR-19..FR-21). Read-only by construction -- there is no
 * edit endpoint here, which is what makes FR-31's distinction structural rather
 * than a UI convention.
 */
@Path("/api/capture")
@Produces(MediaType.APPLICATION_JSON)
public class CaptureResource {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Inject
    CaptureService capture;

    @Inject
    SchemaService schemaService;

    @Inject
    MessageViews views;

    @GET
    @Path("/messages")
    public ObjectNode list(@QueryParam("link") String link,
                           @QueryParam("type") String type,
                           @QueryParam("sort") String sort,
                           @QueryParam("dir") @DefaultValue("asc") String dir,
                           @QueryParam("offset") @DefaultValue("0") int offset,
                           @QueryParam("limit") @DefaultValue("200") int limit,
                           @QueryParam("tail") @DefaultValue("false") boolean tail) {
        int capped = Math.min(Math.max(limit, 1), 1000);
        MessageSort order = MessageSort.parse(sort);
        boolean descending = "desc".equalsIgnoreCase(dir);
        return capture.read(() -> {
            MessageSet set = capture.messages();
            List<MessageEntry> filtered = new ArrayList<>();
            for (MessageEntry entry : set.entries()) {
                if (matches(entry, link, type)) {
                    filtered.add(entry);
                }
            }
            order.apply(filtered, descending);

            int from = tail ? Math.max(0, filtered.size() - capped) : offset;
            ObjectNode node = NODES.objectNode();
            node.put("sort", order.name().toLowerCase(java.util.Locale.ROOT));
            node.put("dir", descending ? "desc" : "asc");
            node.put("total", set.size());
            node.put("filtered", filtered.size());
            node.put("offset", from);
            node.put("limit", capped);
            node.put("overflowed", capture.overflowed());
            ArrayNode items = node.putArray("items");
            for (int i = from; i < Math.min(filtered.size(), from + capped); i++) {
                items.add(views.summary(set, filtered.get(i)));
            }
            return node;
        });
    }

    @GET
    @Path("/messages/{id}")
    public ObjectNode detail(@PathParam("id") long id) {
        return capture.read(() -> {
            MessageEntry entry = capture.messages().byId(id);
            if (entry == null) {
                throw new IllegalArgumentException("no captured message with id " + id);
            }
            return views.detail(capture.messages(), entry);
        });
    }

    private boolean matches(MessageEntry entry, String link, String type) {
        if (link != null && !link.isBlank()) {
            ModuleDef module = schemaService.model().moduleById(entry.moduleId);
            if (module == null || !module.name().equalsIgnoreCase(link)) {
                return false;
            }
        }
        return type == null || type.isBlank() || type.equals(entry.typeName);
    }

    /** FR-21: the capture, byte-exact, in the same format the existing tools read. */
    @GET
    @Path("/export")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response export() {
        StreamingOutput stream = out -> {
            capture.lock().lock();
            try {
                capture.messages().writeTo(out);
            } finally {
                capture.lock().unlock();
            }
        };
        return Response.ok(stream)
                .header("Content-Disposition", "attachment; filename=\"output.bin\"")
                .build();
    }

    @POST
    @Path("/clear")
    public ObjectNode clear() {
        capture.clear();
        ObjectNode node = NODES.objectNode();
        node.put("total", 0);
        return node;
    }
}
