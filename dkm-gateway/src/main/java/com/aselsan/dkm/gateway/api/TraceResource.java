package com.aselsan.dkm.gateway.api;

import com.aselsan.dkm.gateway.capture.CaptureService;
import com.aselsan.dkm.gateway.model.MessageEntry;
import com.aselsan.dkm.gateway.model.MessageSet;
import com.aselsan.dkm.gateway.session.SessionService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * FR-32: one chronological view of everything that actually crossed the wire,
 * in both directions.
 *
 * <p>The two message lists each answer "what is in this set"; neither answers
 * "what happened, in order". Interleaving them is the view that shows a
 * DetectionReport going out and the MeasurementReport it produced coming back
 * two milliseconds later -- the thing an engineer is usually looking for and
 * cannot get by reading two lists side by side.
 *
 * <p>Merged from the same entries the lists are built from, so there is no
 * separate log to fall out of step with reality: if a line is in the trace, its
 * bytes are still on hand and open in the inspector.
 */
@Path("/api/trace")
@Produces(MediaType.APPLICATION_JSON)
public class TraceResource {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Inject
    SessionService session;

    @Inject
    CaptureService capture;

    @Inject
    MessageViews views;

    /**
     * @param since only lines at or after this epoch millisecond; 0 for everything
     * @param limit the newest N lines, returned oldest first
     */
    @GET
    public ObjectNode trace(@QueryParam("since") @DefaultValue("0") long since,
                            @QueryParam("limit") @DefaultValue("500") int limit,
                            @QueryParam("link") String link,
                            @QueryParam("direction") String direction) {
        int capped = Math.min(Math.max(limit, 1), 5000);
        boolean wantOut = direction == null || direction.isBlank() || direction.equalsIgnoreCase("out");
        boolean wantIn = direction == null || direction.isBlank() || direction.equalsIgnoreCase("in");

        List<ObjectNode> rows = new ArrayList<>();

        // Each side is rendered while its own lock is held: a summary reads the
        // message's bytes out of the arena, and the stimulus arena can be
        // rebuilt by a compaction between two calls.
        if (wantOut) {
            session.lock().lock();
            try {
                collect(session.messages(), "OUT", since, link, rows);
            } finally {
                session.lock().unlock();
            }
        }
        if (wantIn) {
            capture.lock().lock();
            try {
                collect(capture.messages(), "IN", since, link, rows);
            } finally {
                capture.lock().unlock();
            }
        }

        rows.sort(Comparator.comparingLong((ObjectNode r) -> r.path("wallClock").asLong())
                .thenComparingLong(r -> r.path("id").asLong()));

        int from = Math.max(0, rows.size() - capped);
        ObjectNode node = NODES.objectNode();
        node.put("total", rows.size());
        node.put("returned", rows.size() - from);
        ArrayNode items = node.putArray("items");
        long previous = 0;
        for (int i = from; i < rows.size(); i++) {
            ObjectNode row = rows.get(i);
            long wallClock = row.path("wallClock").asLong();
            // Gap to the previous line, which is what makes cause and effect
            // legible: a reply two milliseconds after its stimulus reads very
            // differently from one two seconds after.
            row.put("deltaMillis", previous == 0 ? 0 : wallClock - previous);
            previous = wallClock;
            items.add(row);
        }
        return node;
    }

    private void collect(MessageSet set, String direction, long since, String link,
                         List<ObjectNode> into) {
        boolean outbound = "OUT".equals(direction);
        for (MessageEntry entry : set.entries()) {
            if (outbound && !entry.sent) {
                continue;
            }
            if (entry.wallClock == 0 || entry.wallClock < since) {
                continue;
            }
            ObjectNode row = views.summary(set, entry);
            if (link != null && !link.isBlank() && !link.equalsIgnoreCase(row.path("link").asText())) {
                continue;
            }
            row.put("direction", direction);
            into.add(row);
        }
    }
}
