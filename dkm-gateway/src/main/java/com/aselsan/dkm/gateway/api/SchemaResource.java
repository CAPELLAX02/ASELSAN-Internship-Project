package com.aselsan.dkm.gateway.api;

import com.aselsan.dkm.gateway.schema.SchemaService;
import com.aselsan.dkm.gateway.viz.VizCatalog;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The interface as data (FR-1). The UI generates every field editor from this,
 * which is what keeps "add a message type" a schema change rather than a
 * front-end change (G1/NFR-1).
 */
@Path("/api/schema")
@Produces(MediaType.APPLICATION_JSON)
public class SchemaResource {

    @Inject
    SchemaService schemaService;

    @Inject
    VizCatalog vizCatalog;

    @GET
    public ObjectNode schema() {
        return schemaService.description();
    }

    @GET
    @Path("/visualization")
    public ObjectNode visualization() {
        return vizCatalog.description();
    }

    /** The raw schema file, for diffing against a regenerated one. */
    @GET
    @jakarta.ws.rs.Path("/source")
    @Produces(MediaType.APPLICATION_JSON)
    public String source() {
        return schemaService.source();
    }
}
