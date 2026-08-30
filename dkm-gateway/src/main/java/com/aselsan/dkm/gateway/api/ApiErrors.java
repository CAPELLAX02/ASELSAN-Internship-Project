package com.aselsan.dkm.gateway.api;

import com.aselsan.dkm.gateway.schema.CodecException;
import com.aselsan.dkm.gateway.schema.SchemaException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Turns the failures this service actually produces into responses an operator
 * can act on.
 * NFR-5 is mostly about not corrupting data silently, but it is also about
 * this: a validation failure should come back saying which field was wrong and
 * why, not as a 500 with a stack trace.
 */
@Provider
public class ApiErrors implements ExceptionMapper<Throwable> {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Override
    public Response toResponse(Throwable exception) {
        return switch (exception) {
            case CodecException codec -> build(Response.Status.BAD_REQUEST, codec.getMessage(), codec.issues());
            case SchemaException schema -> build(Response.Status.INTERNAL_SERVER_ERROR, schema.getMessage(), null);
            case IllegalArgumentException bad -> build(Response.Status.BAD_REQUEST, bad.getMessage(), null);
            case IllegalStateException conflict -> build(Response.Status.CONFLICT, conflict.getMessage(), null);
            case jakarta.ws.rs.WebApplicationException web ->
                    build(Response.Status.fromStatusCode(web.getResponse().getStatus()), web.getMessage(), null);
            default -> build(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(exception), null);
        };
    }

    private static Response build(Response.Status status, String message, Iterable<String> issues) {
        ObjectNode node = NODES.objectNode();
        node.put("error", status.getReasonPhrase());
        node.put("message", message);
        if (issues != null) {
            ArrayNode array = node.putArray("issues");
            issues.forEach(array::add);
        }
        // The media type has to be explicit: an error body with no Content-Type
        // is unparseable to a client, which turns a clear "field out of range"
        // into an opaque transport failure.
        return Response.status(status).type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON).entity(node).build();
    }
}
