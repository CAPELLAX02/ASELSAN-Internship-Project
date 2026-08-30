package com.aselsan.dkm.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal JSON HTTP client for the integration tests.
 *
 * <p>Hand-rolled on {@code java.net.http} rather than pulling in a test DSL: the
 * tests need to assert on 4xx responses as much as on 2xx ones, and a client
 * that treats an error status as an exception makes exactly those assertions
 * awkward to write.
 */
public final class Api {

    public record Result(int status, JsonNode body, String raw) {
        public JsonNode at(String pointer) {
            return body.at(pointer);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;

    public Api(URL root) {
        String text = root.toString();
        this.base = text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    }

    public Result get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    public Result post(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    public Result post(String path, String json) {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    public Result put(String path, String json) {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)));
    }

    public Result delete(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).DELETE());
    }

    public byte[] getBytes(String path) {
        try {
            return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).body();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("request to " + path + " failed", e);
        }
    }

    private Result send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            JsonNode body = raw == null || raw.isBlank()
                    ? MAPPER.nullNode()
                    : MAPPER.readTree(raw);
            return new Result(response.statusCode(), body, raw);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("request failed", e);
        }
    }
}
