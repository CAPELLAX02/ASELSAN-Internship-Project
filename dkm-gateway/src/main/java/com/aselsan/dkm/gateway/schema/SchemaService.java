package com.aselsan.dkm.gateway.schema;

import com.aselsan.dkm.gateway.wire.Wire;
import com.aselsan.dkm.gateway.wire.WireConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Loads and compiles the interface schema once at startup, and hands the
 * resulting {@link SchemaModel}, {@link MessageCodec} and {@link Wire} to
 * everything else.
 *
 * <p>The schema ships on the classpath, but {@code dkm.schema.path} overrides it
 * with a file on disk. That override is the whole answer to the interface-sync
 * question (§8): when the target's headers change, the operator drops in a
 * regenerated schema file and restarts -- no rebuild of this service, and no
 * code change anywhere.
 */
@Startup
@ApplicationScoped
public class SchemaService {

    private static final Logger LOG = Logger.getLogger(SchemaService.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    public static final String CLASSPATH_SCHEMA = "interface/interface-schema.json";

    @Inject
    ObjectMapper mapper;

    @Inject
    WireConfig wireConfig;

    @ConfigProperty(name = "dkm.schema.path")
    Optional<String> schemaPath;

    private SchemaModel model;
    private MessageCodec codec;
    private Wire wire;
    private String source;
    private ObjectNode description;

    @PostConstruct
    void load() {
        try {
            source = readSource();
            JsonNode root = mapper.readTree(source);
            wire = new Wire(wireConfig.littleEndian());
            model = new SchemaCompiler(wireConfig.sizeTBytes(), wireConfig.littleEndian())
                    .compile(root, source);
            codec = new MessageCodec(model, wire);
            description = buildDescription();

            LOG.infof("Interface schema %s (%s) compiled: %d message type(s) across %d peer link(s); "
                            + "header %d bytes, size_t %d bytes, %s",
                    model.version(), model.hash(), model.messages().size(), model.peerModules().size(),
                    model.headerSize(), model.sizeTBytes(), wire.littleEndian() ? "little-endian" : "big-endian");
            for (CompiledMessage m : model.messages()) {
                LOG.debugf("  %-28s msg_id=%d size=%d %s", m.qualifiedName, m.msgId, m.size, m.direction);
            }
        } catch (IOException e) {
            throw new SchemaException("failed to read the interface schema", e);
        }
    }

    private String readSource() throws IOException {
        if (schemaPath.isPresent() && !schemaPath.get().isBlank()) {
            Path path = Path.of(schemaPath.get());
            LOG.infof("Loading interface schema from %s", path.toAbsolutePath());
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(CLASSPATH_SCHEMA)) {
            if (in == null) {
                throw new SchemaException("classpath resource " + CLASSPATH_SCHEMA + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public SchemaModel model() {
        return model;
    }

    public MessageCodec codec() {
        return codec;
    }

    public Wire wire() {
        return wire;
    }

    public String source() {
        return source;
    }

    /** The schema as the UI consumes it: enough to generate every field editor. */
    public ObjectNode description() {
        return description;
    }

    private ObjectNode buildDescription() {
        ObjectNode root = NODES.objectNode();
        root.put("version", model.version());
        root.put("hash", model.hash());
        root.put("sizeTBytes", model.sizeTBytes());
        root.put("byteOrder", wire.littleEndian() ? "LITTLE_ENDIAN" : "BIG_ENDIAN");
        root.put("headerSize", model.headerSize());

        ArrayNode modules = root.putArray("modules");
        int linkIndex = 0;
        for (ModuleDef m : model.modules()) {
            ObjectNode node = modules.addObject();
            node.put("name", m.name());
            node.put("id", m.id());
            node.put("dkm", m.dkm());
            node.put("port", m.defaultPort());
            node.put("description", m.description());
            // Visualization frames identify a link by index, not by module id --
            // it has to fit in a byte and be an array subscript on both sides.
            // Publishing the index here means the console never has to infer it
            // from declaration order.
            node.put("linkIndex", m.dkm() ? -1 : linkIndex++);
        }

        root.set("header", describeStruct(model.header()));

        ArrayNode structs = root.putArray("structs");
        for (CompiledStruct s : model.structs()) {
            structs.add(describeStruct(s));
        }

        ArrayNode messages = root.putArray("messages");
        for (CompiledMessage m : model.messages()) {
            ObjectNode node = messages.addObject();
            node.put("qualifiedName", m.qualifiedName);
            node.put("name", m.name);
            node.put("module", m.module.name());
            node.put("moduleId", m.module.id());
            node.put("msgId", m.msgId);
            node.put("direction", m.direction.name());
            node.put("size", m.size);
            node.put("doc", m.doc);
            node.put("correlationField", m.correlationField == null ? null : m.correlationField.name);
            node.set("fields", describeFields(m.fields));
        }

        ObjectNode constants = root.putObject("constants");
        model.constants().forEach(constants::put);
        return root;
    }

    private ObjectNode describeStruct(CompiledStruct struct) {
        ObjectNode node = NODES.objectNode();
        node.put("name", struct.name);
        node.put("size", struct.size);
        node.put("alignment", struct.alignment);
        node.set("fields", describeFields(struct.fields));
        return node;
    }

    private ArrayNode describeFields(Iterable<CompiledField> fields) {
        ArrayNode array = NODES.arrayNode();
        for (CompiledField f : fields) {
            ObjectNode node = array.addObject();
            node.put("name", f.name);
            node.put("kind", f.kind.name());
            node.put("type", f.type != null ? f.type.schemaName() : f.struct.name);
            node.put("offset", f.offset);
            node.put("size", f.size);
            node.put("elementSize", f.elementSize);
            node.put("array", f.isArray());
            node.put("arrayLength", f.arrayLength);
            node.put("countField", f.countField);
            node.put("stringLike", f.stringLike);
            node.put("unit", f.unit);
            node.put("doc", f.doc);
            node.put("correlationId", f.correlationId);
            if (!f.enumValues.isEmpty()) {
                ObjectNode enums = node.putObject("enumValues");
                f.enumValues.forEach((k, v) -> enums.put(String.valueOf(k), v));
            }
            if (f.isStructLike()) {
                node.set("struct", describeStruct(f.struct));
            }
            if (f.type != null && !f.type.floating && f.type != FieldType.BOOL) {
                int width = f.type.width(model.sizeTBytes());
                node.put("bits", width * 8);
                node.put("signed", f.type.signed);
            }
        }
        return array;
    }
}
