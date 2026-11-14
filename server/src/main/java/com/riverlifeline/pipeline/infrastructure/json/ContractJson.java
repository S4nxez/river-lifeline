package com.riverlifeline.pipeline.infrastructure.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * The JSON of the worker contract: camelCase keys exactly as written in api/worker-api.yaml. It is its own mapper,
 * independent of the public API's snake_case one.
 */
public final class ContractJson {
    private ContractJson() {}

    public static ObjectMapper mapper() {
        return new ObjectMapper()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
}
