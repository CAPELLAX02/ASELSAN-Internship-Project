package com.aselsan.dkm.gateway.api;

import com.aselsan.dkm.gateway.capture.CaptureService;
import com.aselsan.dkm.gateway.events.EventHub;
import com.aselsan.dkm.gateway.events.LogEntry;
import com.aselsan.dkm.gateway.net.Link;
import com.aselsan.dkm.gateway.net.LinkRegistry;
import com.aselsan.dkm.gateway.playback.PlaybackEngine;
import com.aselsan.dkm.gateway.schema.SchemaService;
import com.aselsan.dkm.gateway.viz.VizPublisher;
import com.aselsan.dkm.gateway.ws.VizHub;
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

/** Connection state, throughput and the session log (FR-15, FR-32). */
@Path("/api/status")
@Produces(MediaType.APPLICATION_JSON)
public class StatusResource {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Inject
    LinkRegistry links;

    @Inject
    PlaybackEngine playback;

    @Inject
    CaptureService capture;

    @Inject
    SchemaService schemaService;

    @Inject
    VizPublisher viz;

    @Inject
    VizHub vizHub;

    @Inject
    EventHub events;

    @GET
    public ObjectNode status() {
        ObjectNode node = NODES.objectNode();
        node.put("schemaVersion", schemaService.model().version());
        node.put("schemaHash", schemaService.model().hash());
        node.set("links", linksNode());
        node.set("playback", playback.snapshot());
        node.set("capture", captureNode());
        node.set("visualization", vizNode());
        return node;
    }

    @GET
    @Path("/links")
    public ArrayNode linksNode() {
        ArrayNode array = NODES.arrayNode();
        for (Link link : links.links()) {
            ObjectNode node = array.addObject();
            node.put("name", link.name());
            node.put("moduleId", link.moduleId());
            node.put("state", link.state().name());
            node.put("detail", link.detail());
            node.put("host", link.host);
            node.put("port", link.port);
            node.put("peer", link.peerAddress());
            node.put("connectedAt", link.connectedAtMillis());
            node.put("bytesIn", link.bytesIn());
            node.put("bytesOut", link.bytesOut());
            node.put("messagesIn", link.messagesIn());
            node.put("messagesOut", link.messagesOut());
            node.put("writeStalls", link.writeStalls());
            node.put("pendingInboundBytes", link.pendingInboundBytes());
        }
        return array;
    }

    private ObjectNode captureNode() {
        ObjectNode node = NODES.objectNode();
        node.put("messages", capture.size());
        node.put("overflowed", capture.overflowed());
        return node;
    }

    private ObjectNode vizNode() {
        ObjectNode node = NODES.objectNode();
        node.put("subscribers", vizHub.subscriberCount());
        node.put("framesSent", vizHub.framesSent());
        node.put("framesSkipped", vizHub.framesSkipped());
        node.put("samplesFromDkm", viz.samplesFromDkm());
        node.put("samplesFromStimulus", viz.samplesFromStimulus());
        node.put("samplesDropped", viz.dropped());
        node.put("stimulusThinned", viz.stimulusThinned());
        node.put("knownBeams", viz.knownBeams());
        return node;
    }

    /** FR-32: the session log, so a reconnecting UI can backfill what it missed. */
    @GET
    @Path("/log")
    public ArrayNode log(@QueryParam("limit") @DefaultValue("500") int limit) {
        ArrayNode array = NODES.arrayNode();
        for (LogEntry entry : events.recentLog(Math.min(Math.max(limit, 1), 2000))) {
            ObjectNode node = array.addObject();
            node.put("seq", entry.seq());
            node.put("t", entry.wallClock());
            node.put("level", entry.level());
            node.put("source", entry.source());
            node.put("message", entry.message());
        }
        return array;
    }
}
