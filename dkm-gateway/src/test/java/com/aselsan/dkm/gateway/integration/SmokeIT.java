package com.aselsan.dkm.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against the <em>packaged</em> application -- the runner jar, or the
 * native binary under {@code -Dnative}.
 *
 * <p>This exists because the native build broke in a way no unit test could
 * see. The schema and the visualization catalog are read through the class
 * loader, and GraalVM embeds no classpath resource it cannot see being used, so
 * the native image shipped without them: the JVM build was perfect and the
 * native binary died on its first line of startup. Anything that only shows up
 * in a real image needs a test that runs a real image.
 *
 * <p>Run with {@code ./mvnw verify -DskipITs=false}, or
 * {@code ./mvnw verify -Dnative} which enables it automatically.
 */
@QuarkusIntegrationTest
class SmokeIT {

    @TestHTTPResource
    URL root;

    private Api api;

    @BeforeEach
    void setUp() {
        api = new Api(root);
    }

    @Test
    @DisplayName("the packaged application starts, compiles the schema and binds all three links")
    void startsAndBinds() {
        Api.Result status = api.get("/api/status");
        assertEquals(200, status.status(), status.raw());

        // If the schema resource did not make it into the image, startup fails
        // outright -- but assert on its content too, so a schema that loads yet
        // compiles to nothing is caught as well.
        assertFalse(status.at("/schemaVersion").asText().isBlank(), "no schema version reported");

        JsonNode links = status.at("/links");
        assertEquals(3, links.size(), "expected the RSP, RSM and CRM links");
        for (JsonNode link : links) {
            String state = link.path("state").asText();
            assertTrue(state.equals("LISTENING") || state.equals("CONNECTED"),
                    link.path("name").asText() + " is " + state + ", not bound: "
                            + link.path("detail").asText());
        }
    }

    @Test
    @DisplayName("the visualization catalog is in the image too, and resolves against the schema")
    void catalogIsPresent() {
        Api.Result schema = api.get("/api/schema");
        assertEquals(200, schema.status());
        int messageTypes = schema.at("/messages").size();
        assertTrue(messageTypes > 0, "the schema compiled to no message types");

        Api.Result catalog = api.get("/api/schema/visualization");
        assertEquals(200, catalog.status(), catalog.raw());
        assertTrue(catalog.at("/mappings").size() > 0, "the visualization catalog compiled to no mappings");
        assertFalse(catalog.at("/conventions/polarToCartesian").asText().isBlank(),
                "the coordinate convention the picture depends on is missing");
    }
}
