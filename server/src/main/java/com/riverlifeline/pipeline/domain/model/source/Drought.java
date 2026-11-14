package com.riverlifeline.pipeline.domain.model.source;

import java.time.LocalDate;
import java.util.Objects;

/** A recorded drought of a region. */
public record Drought(LocalDate start, LocalDate end, int severity) {
    public Drought {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
    }
}
