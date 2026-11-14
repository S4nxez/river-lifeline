package com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads the JSON files of contract 1: snake_case keys, unknown fields ignored (a newer minor version may add them),
 * {@code NaN}/{@code Infinity} rejected.
 */
final class ProcessingJson {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addModule(new JavaTimeModule())
            .build();

    private ProcessingJson() {}

    static JsonNode readTree(Path file) throws IOException {
        return MAPPER.readTree(file.toFile());
    }

    static <T> T read(Path file, Class<T> type) throws IOException {
        return MAPPER.readValue(file.toFile(), type);
    }

    static <T> T convert(JsonNode tree, Class<T> type) throws IOException {
        return MAPPER.treeToValue(tree, type);
    }
}
