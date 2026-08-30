package com.aselsan.dkm.gateway.schema;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the DKM's interface headers and produces the structural half of a
 * schema: modules, constants, struct and message names, msg_ids, and every
 * field's name, type and array length.
 *
 * <h2>Why this exists</h2>
 * §8 leaves "interface sync" open: hand-maintained schema, or codegen from the
 * headers. Both answers are wrong on their own. Codegen alone cannot produce
 * what the headers do not contain -- direction, units, which field is a
 * correlation id, what a message means -- and hand-maintenance alone silently
 * rots the moment someone adds a field to a struct.
 *
 * <p>So the split is: the checked-in schema stays the source of truth and keeps
 * the annotations, and this generator exists to <em>prove</em> it still matches
 * the headers. {@code SchemaDriftTest} runs it on every build and fails when
 * the two disagree, printing what changed. An interface change then becomes a
 * red build with a diff, instead of a simulator that quietly sends the wrong
 * bytes -- which is the actual failure this requirement is trying to prevent.
 *
 * <h2>Scope</h2>
 * This parses the subset of C++ the interface headers actually use -- plain
 * structs of scalars and {@code std::array}, one {@code MsgHeader} member, a
 * {@code kMsgId} constant. It does not attempt to be a C++ parser, and it fails
 * loudly on anything it does not recognise rather than guessing. That is the
 * right trade for a drift detector: a false alarm costs a minute, a missed
 * change costs a debugging session against wrong bytes on a wire.
 */
public final class HeaderSchemaGenerator {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    // A `static constexpr` inside a struct is that struct's own metadata
    // (kMsgId), not an interface-wide constant. The `static` prefix is captured
    // so it can be excluded rather than silently pulling kMsgId into constants.
    private static final Pattern CONSTANT = Pattern.compile(
            "(static\\s+)?constexpr\\s+(?:std::)?size_t\\s+(\\w+)\\s*=\\s*(\\d+)\\s*;");
    private static final Pattern MODULE_ENUM = Pattern.compile(
            "enum\\s+class\\s+ModuleId\\s*:\\s*(?:std::)?size_t\\s*\\{([^}]*)}");
    private static final Pattern MODULE_ENTRY = Pattern.compile("(\\w+)\\s*=\\s*(\\d+)");
    private static final Pattern MSG_ID = Pattern.compile(
            "static\\s+constexpr\\s+(?:std::)?size_t\\s+kMsgId\\s*=\\s*(\\d+)\\s*;");
    private static final Pattern ARRAY_FIELD = Pattern.compile(
            "std::array\\s*<\\s*([\\w:]+)\\s*,\\s*([\\w:]+)\\s*>\\s+(\\w+)\\s*(?:\\{[^;]*})?\\s*;");
    private static final Pattern SCALAR_FIELD = Pattern.compile(
            "(?:^|[;{}\\n])\\s*((?:unsigned\\s+|signed\\s+)?(?:std::)?[\\w]+)\\s+(\\w+)\\s*(?:=\\s*[^;]+|\\{[^;]*})?\\s*;");

    /** Header file base name -> the module its message types belong to. */
    private final Map<String, String> moduleByFile;

    public HeaderSchemaGenerator(Map<String, String> moduleByFile) {
        this.moduleByFile = Map.copyOf(moduleByFile);
    }

    /** The mapping used by this repo's headers. */
    public static HeaderSchemaGenerator forMockR() {
        return new HeaderSchemaGenerator(Map.of("rsp.h", "RSP", "rsm.h", "RSM", "crm.h", "CRM"));
    }

    /**
     * @param includeDir directory holding {@code common.h}, {@code def.h} and
     *                   the per-module headers
     */
    public ObjectNode generate(Path includeDir) throws IOException {
        Map<String, Integer> constants = new LinkedHashMap<>();
        Map<String, Long> modules = new LinkedHashMap<>();
        List<ParsedStruct> headerStructs = new ArrayList<>();
        List<ParsedStruct> plainStructs = new ArrayList<>();
        List<ParsedStruct> messages = new ArrayList<>();
        Set<String> knownStructNames = new LinkedHashSet<>();

        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(includeDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".h")).sorted().forEach(files::add);
        }
        if (files.isEmpty()) {
            throw new SchemaException("no headers found in " + includeDir.toAbsolutePath());
        }

        for (Path file : files) {
            String source = strip(Files.readString(file, StandardCharsets.UTF_8));
            String fileName = file.getFileName().toString();

            Matcher constant = CONSTANT.matcher(source);
            while (constant.find()) {
                if (constant.group(1) != null) {
                    continue;
                }
                constants.put(constant.group(2), Integer.parseInt(constant.group(3)));
            }

            Matcher moduleEnum = MODULE_ENUM.matcher(source);
            if (moduleEnum.find()) {
                Matcher entry = MODULE_ENTRY.matcher(moduleEnum.group(1));
                while (entry.find()) {
                    modules.put(entry.group(1), Long.parseLong(entry.group(2)));
                }
            }

            for (ParsedStruct parsed : parseStructs(source, fileName)) {
                knownStructNames.add(parsed.name);
                if (parsed.name.equals("MsgHeader")) {
                    headerStructs.add(parsed);
                } else if (parsed.msgId != null) {
                    messages.add(parsed);
                } else {
                    plainStructs.add(parsed);
                }
            }
        }

        if (headerStructs.isEmpty()) {
            throw new SchemaException("no MsgHeader struct found under " + includeDir.toAbsolutePath());
        }
        if (modules.isEmpty()) {
            throw new SchemaException("no ModuleId enum found under " + includeDir.toAbsolutePath());
        }

        return build(constants, modules, headerStructs.get(0), plainStructs, messages, knownStructNames);
    }

    private ObjectNode build(Map<String, Integer> constants, Map<String, Long> modules,
                             ParsedStruct header, List<ParsedStruct> structs,
                             List<ParsedStruct> messages, Set<String> knownStructNames) {
        ObjectNode root = NODES.objectNode();

        ObjectNode constantsNode = root.putObject("constants");
        constants.forEach(constantsNode::put);

        ArrayNode modulesNode = root.putArray("modules");
        modules.forEach((name, id) -> {
            ObjectNode node = modulesNode.addObject();
            node.put("name", name);
            node.put("id", id);
        });

        root.set("header", structNode(header, knownStructNames));

        ArrayNode structsNode = root.putArray("structs");
        structs.stream()
                .sorted((a, b) -> a.name.compareTo(b.name))
                .forEach(s -> structsNode.add(structNode(s, knownStructNames)));

        ArrayNode messagesNode = root.putArray("messages");
        messages.stream()
                .sorted((a, b) -> a.name.compareTo(b.name))
                .forEach(m -> {
                    ObjectNode node = structNode(m, knownStructNames);
                    node.put("msgId", m.msgId);
                    String module = moduleByFile.get(m.fileName);
                    if (module == null) {
                        throw new SchemaException(m.name + " is declared in " + m.fileName
                                + ", which is not mapped to a module -- add it to the generator's file map");
                    }
                    node.put("module", module);
                    messagesNode.add(node);
                });
        return root;
    }

    private ObjectNode structNode(ParsedStruct parsed, Set<String> knownStructNames) {
        ObjectNode node = NODES.objectNode();
        node.put("name", parsed.name);
        ArrayNode fields = node.putArray("fields");
        for (ParsedField field : parsed.fields) {
            ObjectNode f = fields.addObject();
            f.put("name", field.name);
            FieldType primitive = FieldType.parse(field.type);
            if (primitive == null && !knownStructNames.contains(field.type)) {
                throw new SchemaException(parsed.name + "." + field.name + ": type '" + field.type
                        + "' is neither a known primitive nor a struct declared in these headers");
            }
            f.put("type", primitive != null ? primitive.schemaName() : field.type);
            if (field.arrayLengthConstant != null) {
                ObjectNode array = f.putObject("array");
                array.put("lengthConstant", field.arrayLengthConstant);
            }
        }
        return node;
    }

    // ---- parsing ---------------------------------------------------------

    private record ParsedField(String type, String name, String arrayLengthConstant) {
    }

    private static final class ParsedStruct {
        final String name;
        final String fileName;
        Long msgId;
        final List<ParsedField> fields = new ArrayList<>();

        ParsedStruct(String name, String fileName) {
            this.name = name;
            this.fileName = fileName;
        }
    }

    private static String strip(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll(" ")).replaceAll(" ");
    }

    private List<ParsedStruct> parseStructs(String source, String fileName) {
        List<ParsedStruct> result = new ArrayList<>();
        Matcher declaration = Pattern.compile("struct\\s+(\\w+)\\s*\\{").matcher(source);
        while (declaration.find()) {
            String name = declaration.group(1);
            int open = declaration.end() - 1;
            int close = matchingBrace(source, open);
            if (close < 0) {
                throw new SchemaException("unbalanced braces in struct " + name + " (" + fileName + ")");
            }
            String body = source.substring(open + 1, close);
            result.add(parseBody(name, fileName, body));
            declaration.region(close, source.length());
        }
        return result;
    }

    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private ParsedStruct parseBody(String name, String fileName, String body) {
        ParsedStruct parsed = new ParsedStruct(name, fileName);

        Matcher msgId = MSG_ID.matcher(body);
        if (msgId.find()) {
            parsed.msgId = Long.parseLong(msgId.group(1));
        }

        // Arrays first, then blank them out so the scalar pass cannot
        // misinterpret the leftovers.
        StringBuilder remainder = new StringBuilder(body);
        Matcher array = ARRAY_FIELD.matcher(body);
        record Slot(int start, int end, ParsedField field) {
        }
        List<Slot> slots = new ArrayList<>();
        while (array.find()) {
            slots.add(new Slot(array.start(), array.end(),
                    new ParsedField(array.group(1), array.group(3), array.group(2))));
        }
        for (Slot slot : slots) {
            for (int i = slot.start(); i < slot.end(); i++) {
                remainder.setCharAt(i, ' ');
            }
        }

        // Same for the kMsgId line, which is metadata rather than a field.
        Matcher msgIdAgain = MSG_ID.matcher(body);
        while (msgIdAgain.find()) {
            for (int i = msgIdAgain.start(); i < msgIdAgain.end(); i++) {
                remainder.setCharAt(i, ' ');
            }
        }

        record Positioned(int at, ParsedField field) {
        }
        List<Positioned> positioned = new ArrayList<>();
        for (Slot slot : slots) {
            positioned.add(new Positioned(slot.start(), slot.field()));
        }

        Matcher scalar = SCALAR_FIELD.matcher(remainder.toString());
        while (scalar.find()) {
            String type = scalar.group(1).trim();
            String field = scalar.group(2);
            if (type.equals("static") || type.equals("constexpr") || type.equals("return")) {
                continue;
            }
            positioned.add(new Positioned(scalar.start(2), new ParsedField(type, field, null)));
        }

        positioned.sort((a, b) -> Integer.compare(a.at(), b.at()));
        for (Positioned entry : positioned) {
            // The embedded MsgHeader is the message's header, not one of its
            // payload fields -- the schema models it separately, since every
            // message shares it and framing depends on it.
            if (entry.field().type().equals("MsgHeader")) {
                continue;
            }
            parsed.fields.add(entry.field());
        }
        return parsed;
    }

    /** {@code mvn exec} entry point: prints the structural schema for a given include directory. */
    public static void main(String[] args) throws IOException {
        Path includeDir = Path.of(args.length > 0 ? args[0] : "../dkm-simulator/mock_r/inc/interface");
        ObjectNode generated = forMockR().generate(includeDir);
        System.out.println(new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(generated));
    }
}
