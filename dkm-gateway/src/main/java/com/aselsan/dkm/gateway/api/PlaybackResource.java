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
    public ObjectNode start() {
        engine.start();
        return engine.snapshot();
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
