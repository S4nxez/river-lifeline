package com.riverlifeline.pipeline.domain.model.source;

import java.util.Objects;

/**
 * A trained drought model as plain data. {@code parametersJson} is the model's parameters as the worker wrote them
 * (a JSON object): the backend stores and returns it as is and never executes it.
 */
public record DroughtModel(String kind, int version, int lookbackDays, String parametersJson) {
    public DroughtModel {
        Objects.requireNonNull(kind, "kind");
        if (version < 1 || lookbackDays < 1) {
            throw new IllegalArgumentException("version and lookbackDays must be positive");
        }
        Objects.requireNonNull(parametersJson, "parametersJson");
    }
}
