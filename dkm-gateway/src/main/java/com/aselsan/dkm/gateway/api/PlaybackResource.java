package com.aselsan.dkm.gateway.api;

import com.aselsan.dkm.gateway.playback.PaceMode;
import com.aselsan.dkm.gateway.playback.PlaybackEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/** Transport controls (FR-11, FR-12). */
@Path("/api/playback")
@Produces(MediaType.APPLICATION_JSON)
public class PlaybackResource {

    @Inject
    PlaybackEngine engine;

    @GET
    public ObjectNode state() {
        return engine.snapshot();
    }

    @POST
    @Path("/start")
    public ObjectNode start(@QueryParam("from") @DefaultValue("0") long fromMessageId) {
        engine.start(fromMessageId);
        return engine.snapshot();
    }

    /**
     * FR-11: send the next few messages and wait, for a DKM that needs time.
     *
     * <p>{@code from} steps from a chosen message instead of continuing, which
     * is how an operator gets to the interesting part of a long recording
     * without hand-stepping through everything in front of it.
     */
    @POST
    @Path("/step")
    public ObjectNode step(@QueryParam("count") @DefaultValue("1") int count,
                           @QueryParam("from") @DefaultValue("0") long from) {
        int sent = engine.step(Math.min(Math.max(count, 1), 1000), from);
        ObjectNode node = engine.snapshot();
        node.put("stepped", sent);
        return node;
    }

    @POST
    @Path("/pause")
    public ObjectNode pause() {
        engine.pause();
        return engine.snapshot();
    }

    @POST
    @Path("/resume")
    public ObjectNode resume() {
        engine.resume();
        return engine.snapshot();
    }

    /**
     * {@code rewind=true} (the default) returns the run to message zero and makes
     * the whole set editable again; {@code rewind=false} aborts in place and
     * keeps already-sent messages as history. The requirements leave this open
     * (§8) -- both behaviours are here so the choice can be made from experience
     * rather than guessed at now.
     */
    @POST
    @Path("/stop")
    public ObjectNode stop(@QueryParam("rewind") @DefaultValue("true") boolean rewind) {
        engine.stop(rewind);
        return engine.snapshot();
    }

    @PUT
    @Path("/speed")
    @Consumes(MediaType.APPLICATION_JSON)
    public ObjectNode speed(JsonNode body) {
        engine.setSpeed(body.path("speed").asDouble(1.0));
        return engine.snapshot();
    }

    @PUT
    @Path("/mode")
    @Consumes(MediaType.APPLICATION_JSON)
    public ObjectNode mode(JsonNode body) {
        engine.setMode(PaceMode.valueOf(body.path("mode").asText("TIMESTAMP").toUpperCase(java.util.Locale.ROOT)));
        return engine.snapshot();
    }
}
