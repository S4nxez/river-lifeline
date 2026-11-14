package com.riverlifeline.pipeline.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * The schemas of api/worker-api.yaml, and a small validator for the JSON Schema keywords the contract uses (type,
 * required, properties, additionalProperties, items, enum, const, pattern, minimum, maximum, minItems, maxItems, $ref,
 * allOf, oneOf). It lets tests check that what the backend writes is what the contract says.
 */
public final class ContractSchema {
    private static Map<String, Object> schemas;

    private ContractSchema() {}

    @SuppressWarnings("unchecked")
    static synchronized Map<String, Object> schemas() {
        if (schemas == null) {
            try (InputStream in = Files.newInputStream(Path.of("..", "api", "worker-api.yaml"))) {
                Map<String, Object> spec = new Yaml().load(in);
                schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
            } catch (IOException e) {
                throw new IllegalStateException("api/worker-api.yaml not found", e);
            }
        }
        return schemas;
    }

    /** Every violation of the named schema (empty when the JSON is valid). */
    public static List<String> violations(JsonNode json, String schemaName) {
        List<String> errors = new ArrayList<>();
        validate(json, schema(schemaName), "$", errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String name) {
        Object s = schemas().get(name);
        if (s == null) {
            throw new IllegalArgumentException("No schema " + name);
        }
        return (Map<String, Object>) s;
    }

    @SuppressWarnings("unchecked")
    private static void validate(JsonNode node, Map<String, Object> s, String at, List<String> errors) {
        if (s.containsKey("$ref")) {
            String ref = (String) s.get("$ref");
            validate(node, schema(ref.substring(ref.lastIndexOf('/') + 1)), at, errors);
        }
        if (s.containsKey("allOf")) {
            for (Object part : (List<Object>) s.get("allOf")) {
                Map<String, Object> p = (Map<String, Object>) part;
                if (!p.containsKey("if")) {
                    validate(node, p, at, errors);
                }
            }
        }
        if (s.containsKey("oneOf")) {
            int matching = 0;
            List<String> first = null;
            for (Object option : (List<Object>) s.get("oneOf")) {
                List<String> e = new ArrayList<>();
                validate(node, (Map<String, Object>) option, at, e);
                if (e.isEmpty()) {
                    matching++;
                } else if (first == null) {
                    first = e;
                }
            }
            if (matching == 0) {
                errors.add(at + ": matches no option" + (first == null ? "" : " (" + first.get(0) + ")"));
            }
        }
        if (s.containsKey("type") && !typeMatches(node, s.get("type"))) {
            errors.add(at + ": expected " + s.get("type") + ", got " + node.getNodeType());
            return;
        }
        if (s.containsKey("const") && !constMatches(node, s.get("const"))) {
            errors.add(at + ": expected " + s.get("const"));
        }
        if (s.containsKey("enum") && (node.isNull() || !((List<Object>) s.get("enum")).contains(node.asText()))) {
            errors.add(at + ": " + node + " not in " + s.get("enum"));
        }
        if (s.containsKey("pattern")
                && node.isTextual()
                && !Pattern.compile((String) s.get("pattern"))
                        .matcher(node.asText())
                        .find()) {
            errors.add(at + ": " + node.asText() + " does not match " + s.get("pattern"));
        }
        if (s.containsKey("minimum")
                && node.isNumber()
                && node.asDouble() < ((Number) s.get("minimum")).doubleValue()) {
            errors.add(at + ": below " + s.get("minimum"));
        }
        if (s.containsKey("maximum")
                && node.isNumber()
                && node.asDouble() > ((Number) s.get("maximum")).doubleValue()) {
            errors.add(at + ": above " + s.get("maximum"));
        }
        if (node.isObject()) {
            Map<String, Object> properties = (Map<String, Object>) s.getOrDefault("properties", Map.of());
            for (Object required : (List<Object>) s.getOrDefault("required", List.of())) {
                if (!node.has((String) required)) {
                    errors.add(at + ": " + required + " is missing");
                }
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                Object property = properties.get(field.getKey());
                if (property != null) {
                    validate(field.getValue(), (Map<String, Object>) property, at + "." + field.getKey(), errors);
                } else if (Boolean.FALSE.equals(s.get("additionalProperties"))) {
                    errors.add(at + ": unexpected " + field.getKey());
                } else if (s.get("additionalProperties") instanceof Map<?, ?> extra) {
                    validate(field.getValue(), (Map<String, Object>) extra, at + "." + field.getKey(), errors);
                }
            }
        }
        if (node.isArray()) {
            if (s.containsKey("minItems") && node.size() < ((Number) s.get("minItems")).intValue()) {
                errors.add(at + ": fewer than " + s.get("minItems") + " items");
            }
            if (s.containsKey("maxItems") && node.size() > ((Number) s.get("maxItems")).intValue()) {
                errors.add(at + ": more than " + s.get("maxItems") + " items");
            }
            if (s.containsKey("items")) {
                for (int i = 0; i < node.size(); i++) {
                    validate(node.get(i), (Map<String, Object>) s.get("items"), at + "[" + i + "]", errors);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean typeMatches(JsonNode node, Object type) {
        if (type instanceof List<?> types) {
            return types.stream().anyMatch(t -> typeMatches(node, t));
        }
        return switch ((String) type) {
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            case "string" -> node.isTextual();
            case "integer" ->
                node.isIntegralNumber() || (node.isNumber() && node.asDouble() == Math.rint(node.asDouble()));
            case "number" -> node.isNumber();
            case "boolean" -> node.isBoolean();
            case "null" -> node.isNull();
            default -> false;
        };
    }

    private static boolean constMatches(JsonNode node, Object value) {
        if (value instanceof Number n) {
            return node.isNumber() && node.asDouble() == n.doubleValue();
        }
        return node.isTextual() && node.asText().equals(String.valueOf(value));
    }
}
