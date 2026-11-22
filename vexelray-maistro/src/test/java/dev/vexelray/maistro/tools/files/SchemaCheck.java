package dev.vexelray.maistro.tools.files;

import dev.vexelray.maistro.agent.JsonValue;

import java.util.ArrayList;
import java.util.List;

/**
 * The part of JSON Schema the tools' schemas use, checked against a value: {@code type}, {@code properties},
 * {@code required}, {@code additionalProperties: false}, {@code items}, {@code enum}, {@code minimum},
 * {@code maximum}, {@code minItems} and {@code maxItems}. The CLI validates a structured result against its tool's
 * output schema, so a test holds every result to it here first. A keyword the tools do not use is a failure, so the
 * check never passes by not knowing what it was asked.
 */
final class SchemaCheck {

    private static final List<String> KNOWN = List.of("type", "properties", "required", "additionalProperties",
            "items", "enum", "minimum", "maximum", "minItems", "maxItems", "description");

    private SchemaCheck() {
    }

    /** What is wrong with {@code value} against {@code schema}; empty when nothing is. */
    static List<String> problems(JsonValue schema, JsonValue value) {
        List<String> out = new ArrayList<>();
        check(schema, value, "$", out);
        return out;
    }

    private static void check(JsonValue schema, JsonValue v, String at, List<String> out) {
        for (String k : schema.members().keySet()) {
            if (!KNOWN.contains(k)) {
                out.add(at + ": the schema uses '" + k + "', which this check does not know");
            }
        }
        String type = schema.get("type").asString();
        boolean typed = switch (type) {
            case "object" -> v.isObject();
            case "array" -> v.isArray();
            case "string" -> v.isString();
            case "boolean" -> v.isBool();
            case "integer" -> v.isNumber() && v.asDouble(0.5) == Math.rint(v.asDouble(0.5));
            case "number" -> v.isNumber();
            case "" -> true;
            default -> {
                out.add(at + ": unknown type " + type);
                yield false;
            }
        };
        if (!typed) {
            out.add(at + ": " + v.toJson() + " is not " + type);
            return;
        }
        if (schema.has("enum") && schema.get("enum").items().stream().noneMatch(e -> e.toJson().equals(v.toJson()))) {
            out.add(at + ": " + v.toJson() + " is not one of " + schema.get("enum").toJson());
        }
        if (schema.has("minimum") && v.asDouble(0) < schema.get("minimum").asDouble(0)) {
            out.add(at + ": below the minimum");
        }
        if (schema.has("maximum") && v.asDouble(0) > schema.get("maximum").asDouble(0)) {
            out.add(at + ": above the maximum");
        }
        if (v.isObject()) {
            JsonValue props = schema.get("properties");
            for (JsonValue r : schema.get("required").items()) {
                if (!v.has(r.asString())) {
                    out.add(at + ": '" + r.asString() + "' is required");
                }
            }
            for (String k : v.members().keySet()) {
                if (props.has(k)) {
                    check(props.get(k), v.get(k), at + "." + k, out);
                } else if (schema.has("additionalProperties") && !schema.get("additionalProperties").asBool(true)
                        || !props.isNull()) {
                    out.add(at + ": '" + k + "' is not in the schema");
                }
            }
        }
        if (v.isArray()) {
            if (schema.has("minItems") && v.size() < schema.get("minItems").asLong(0)) {
                out.add(at + ": fewer items than minItems");
            }
            if (schema.has("maxItems") && v.size() > schema.get("maxItems").asLong(0)) {
                out.add(at + ": more items than maxItems");
            }
            for (int i = 0; i < v.size(); i++) {
                check(schema.get("items"), v.get(i), at + "[" + i + "]", out);
            }
        }
    }
}
