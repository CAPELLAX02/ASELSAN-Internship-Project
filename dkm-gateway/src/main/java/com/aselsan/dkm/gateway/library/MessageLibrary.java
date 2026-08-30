package com.aselsan.dkm.gateway.library;

import com.aselsan.dkm.gateway.events.EventHub;
import com.aselsan.dkm.gateway.model.MessageEntry;
import com.aselsan.dkm.gateway.schema.CompiledMessage;
import com.aselsan.dkm.gateway.schema.SchemaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.Unpooled;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Persistent store of frequently-used messages, independent of any one input
 * file (FR-22, FR-23).
 *
 * <p>Flat JSON files rather than an embedded database -- the open question in
 * §8, decided this way deliberately. The store is a few hundred small records
 * that an engineer will want to read, hand-edit, copy between machines, review
 * in a diff and check into version control alongside a test scenario. A database
 * file gives up all of that, and buys query performance this never needs.
 */
@ApplicationScoped
public class MessageLibrary {

    private static final Logger LOG = Logger.getLogger(MessageLibrary.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Inject
    ObjectMapper mapper;

    @Inject
    SchemaService schemaService;

    @Inject
    EventHub events;

    @ConfigProperty(name = "dkm.data-dir", defaultValue = "./data")
    String dataDir;

    private final Map<String, LibraryEntry> entries = new ConcurrentHashMap<>();
    private Path directory;
    /** Non-null when the store could not be opened; every operation then explains why. */
    private volatile String unavailableReason;

    /**
     * The library is convenience, not function: the simulator's job is to
     * stimulate the DKM and capture what comes back, and it can do all of that
     * with nowhere to save favourites. A read-only deployment therefore
     * disables the library loudly instead of refusing to start -- taking the
     * whole tool down over an optional directory would be the wrong trade.
     */
    void onStart(@Observes StartupEvent event) {
        directory = Path.of(dataDir, "library");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            unavailableReason = "the message library is disabled: " + directory.toAbsolutePath()
                    + " could not be opened (" + e.getMessage() + "). Everything else works;"
                    + " set dkm.data-dir to a writable location to enable it.";
            LOG.warn(unavailableReason);
            events.warn("library", unavailableReason);
            return;
        }
        reload();
    }

    public boolean isAvailable() {
        return unavailableReason == null;
    }

    public String unavailableReason() {
        return unavailableReason;
    }

    private void requireAvailable() {
        String reason = unavailableReason;
        if (reason != null) {
            throw new IllegalStateException(reason);
        }
    }

    public void reload() {
        if (!isAvailable()) {
            return;
        }
        entries.clear();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(this::loadOne);
        } catch (IOException e) {
            LOG.warnf(e, "could not list the message library at %s", directory);
        }
        long stale = entries.values().stream()
                .filter(e -> e.isStaleAgainst(schemaService.model().hash()))
                .count();
        LOG.infof("message library: %d entr(ies) from %s%s", entries.size(), directory,
                stale > 0 ? ", " + stale + " saved against a different interface" : "");
        if (stale > 0) {
            events.warn("library", stale + " library entr(ies) were saved against a different interface version "
                    + "and are flagged stale -- check them before sending");
        }
    }

    private void loadOne(Path file) {
        try {
            JsonNode node = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            LibraryEntry entry = new LibraryEntry(
                    node.path("id").asText(),
                    node.path("name").asText(),
                    node.path("description").asText(""),
                    toList(node.path("tags")),
                    node.path("typeName").asText(),
                    node.path("moduleId").asLong(),
                    node.path("msgId").asLong(),
                    node.path("length").asInt(),
                    node.path("schemaVersion").asText(""),
                    node.path("schemaHash").asText(""),
                    node.path("createdAt").asLong(),
                    node.path("bytes").asText(""),
                    node.get("payload"));
            entries.put(entry.id(), entry);
        } catch (IOException e) {
            LOG.warnf(e, "skipping unreadable library entry %s", file);
        }
    }

    private static List<String> toList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(v -> values.add(v.asText()));
        }
        return values;
    }

    /** FR-22: save a message exactly as currently edited. */
    public LibraryEntry save(String name, String description, List<String> tags,
                             CompiledMessage type, byte[] bytes) {
        requireAvailable();
        String id = UUID.randomUUID().toString();
        JsonNode payload = schemaService.codec()
                .decode(type, Unpooled.wrappedBuffer(bytes), 0, bytes.length)
                .get("payload");
        LibraryEntry entry = new LibraryEntry(id, name, description == null ? "" : description,
                tags == null ? List.of() : List.copyOf(tags),
                type.qualifiedName, type.module.id(), type.msgId, bytes.length,
                schemaService.model().version(), schemaService.model().hash(),
                System.currentTimeMillis(), Base64.getEncoder().encodeToString(bytes), payload);
        persist(entry);
        entries.put(id, entry);
        events.info("library", "saved '" + name + "' (" + type.qualifiedName + ")");
        return entry;
    }

    private void persist(LibraryEntry entry) {
        ObjectNode node = NODES.objectNode();
        node.put("id", entry.id());
        node.put("name", entry.name());
        node.put("description", entry.description());
        node.set("tags", mapper.valueToTree(entry.tags()));
        node.put("typeName", entry.typeName());
        node.put("moduleId", entry.moduleId());
        node.put("msgId", entry.msgId());
        node.put("length", entry.length());
        node.put("schemaVersion", entry.schemaVersion());
        node.put("schemaHash", entry.schemaHash());
        node.put("createdAt", entry.createdAt());
        node.put("bytes", entry.bytesBase64());
        node.set("payload", entry.payload());
        try {
            Files.writeString(directory.resolve(entry.id() + ".json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not save library entry " + entry.id(), e);
        }
    }

    public boolean delete(String id) {
        requireAvailable();
        LibraryEntry removed = entries.remove(id);
        if (removed == null) {
            return false;
        }
        try {
            Files.deleteIfExists(directory.resolve(id + ".json"));
        } catch (IOException e) {
            LOG.warnf(e, "library entry %s removed from memory but its file could not be deleted", id);
        }
        events.info("library", "deleted '" + removed.name() + "'");
        return true;
    }

    public LibraryEntry get(String id) {
        return entries.get(id);
    }

    public byte[] bytesOf(LibraryEntry entry) {
        return Base64.getDecoder().decode(entry.bytesBase64());
    }

    /** FR-23: browse and search. */
    public List<LibraryEntry> search(String query, String typeName) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return entries.values().stream()
                .filter(e -> typeName == null || typeName.isBlank() || typeName.equals(e.typeName()))
                .filter(e -> needle.isEmpty()
                        || e.name().toLowerCase(Locale.ROOT).contains(needle)
                        || e.description().toLowerCase(Locale.ROOT).contains(needle)
                        || e.typeName().toLowerCase(Locale.ROOT).contains(needle)
                        || e.tags().stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(needle)))
                .sorted(Comparator.comparingLong(LibraryEntry::createdAt).reversed())
                .toList();
    }

    public ObjectNode describe(LibraryEntry entry) {
        ObjectNode node = NODES.objectNode();
        node.put("id", entry.id());
        node.put("name", entry.name());
        node.put("description", entry.description());
        node.set("tags", mapper.valueToTree(entry.tags()));
        node.put("typeName", entry.typeName());
        node.put("moduleId", entry.moduleId());
        node.put("msgId", entry.msgId());
        node.put("length", entry.length());
        node.put("schemaVersion", entry.schemaVersion());
        node.put("createdAt", entry.createdAt());
        node.put("stale", entry.isStaleAgainst(schemaService.model().hash()));
        node.set("payload", entry.payload());
        return node;
    }

    public Path directory() {
        return directory;
    }

    /** Convenience for the "save the selected message" flow. */
    public LibraryEntry saveFrom(MessageEntry entry, byte[] bytes, String name, String description,
                                 List<String> tags) {
        CompiledMessage type = entry.typeName == null ? null : schemaService.model().message(entry.typeName);
        if (type == null) {
            throw new IllegalArgumentException("message " + entry.id
                    + " has no schema type and cannot be saved to the library");
        }
        return save(name, description, tags, type, bytes);
    }
}
