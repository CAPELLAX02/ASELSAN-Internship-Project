package com.aselsan.dkm.gateway.api;

import com.aselsan.dkm.gateway.model.MessageEntry;
import com.aselsan.dkm.gateway.model.MessageSet;
import com.aselsan.dkm.gateway.schema.CompiledField;
import com.aselsan.dkm.gateway.schema.CompiledMessage;
import com.aselsan.dkm.gateway.schema.ModuleDef;
import com.aselsan.dkm.gateway.schema.SchemaService;
import com.aselsan.dkm.gateway.viz.VizCatalog;
import com.aselsan.dkm.gateway.viz.VizExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Shapes messages for the UI: a cheap row for the list, a full decode for the
 * editor.
 *
 * <p>The list row deliberately does not decode the whole message. A capture can
 * run to millions of messages and the list only ever shows a page of them, so
 * decoding is done per page and the summary line is built from the first few
 * scalar fields -- generated from the schema, so a new message type gets a
 * sensible row with no code change (NFR-1).
 */
@ApplicationScoped
public class MessageViews {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int PREVIEW_FIELDS = 4;

    @Inject
    SchemaService schemaService;

    @Inject
    VizCatalog vizCatalog;

    public ObjectNode summary(MessageSet set, MessageEntry entry) {
        ObjectNode node = NODES.objectNode();
        node.put("id", entry.id);
        node.put("moduleId", entry.moduleId);
        ModuleDef module = schemaService.model().moduleById(entry.moduleId);
        node.put("link", module == null ? null : module.name());
        node.put("msgId", entry.msgId);
        node.put("timestamp", entry.timestamp);
        node.put("length", entry.length);
        node.put("type", entry.typeName);
        node.put("problem", entry.problem);
        node.put("origin", entry.origin == null ? null : entry.origin.name());
        node.put("sent", entry.sent);
        node.put("wallClock", entry.wallClock);

        CompiledMessage type = entry.typeName == null ? null : schemaService.model().message(entry.typeName);
        node.put("direction", type == null ? null : type.direction.name());
        node.put("editable", entry.isEditable() && type != null && entry.problem == null);
        if (type != null) {
            VizExtractor extractor = vizCatalog.extractor(type.qualifiedName);
            node.put("vizKind", extractor == null ? "NONE" : extractor.kind.name());
        }
        node.put("preview", preview(set, entry, type));
        return node;
    }

    /** The full field tree the editor renders (FR-30). */
    public ObjectNode detail(MessageSet set, MessageEntry entry) {
        ObjectNode node = summary(set, entry);
        CompiledMessage type = entry.typeName == null ? null : schemaService.model().message(entry.typeName);
        if (type == null || entry.problem != null) {
            node.set("header", schemaService.codec().decodeHeader(set.view(entry), 0));
            node.putNull("payload");
            node.put("decodable", false);
            return node;
        }
        JsonNode decoded = schemaService.codec().decode(type, set.view(entry), 0, entry.length);
        node.set("header", decoded.get("header"));
        node.set("payload", decoded.get("payload"));
        node.put("decodable", true);
        return node;
    }

    private String preview(MessageSet set, MessageEntry entry, CompiledMessage type) {
        if (type == null) {
            return "raw " + entry.length + " bytes, msg_id=" + entry.msgId;
        }
        if (entry.problem != null) {
            return entry.problem;
        }
        StringBuilder text = new StringBuilder();
        var view = set.view(entry);
        int shown = 0;
        for (CompiledField field : type.fields) {
            if (shown >= PREVIEW_FIELDS) {
                text.append(", ...");
                break;
            }
            if (field.type == null) {
                continue;
            }
            if (shown > 0) {
                text.append(", ");
            }
            text.append(field.name).append('=');
            if (field.isArray()) {
                text.append('[').append(field.arrayLength).append(']');
            } else if (field.type.floating) {
                text.append(format(schemaService.codec().readNumeric(field.type, view, field.offset)));
            } else {
                text.append(schemaService.codec().readInteger(field.type, view, field.offset));
            }
            shown++;
        }
        return text.toString();
    }

    private static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e12) {
            return String.valueOf((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.4g", value);
    }
}
