package com.riverlifeline.pipeline.domain.model.source;

import java.time.LocalDate;
import java.util.Objects;

/** Daily median river level at a gauge, in centimetres. */
public record GaugeDay(LocalDate day, double levelCm) {
    public GaugeDay {
        Objects.requireNonNull(day, "day");
        if (!Double.isFinite(levelCm)) {
            throw new IllegalArgumentException("Level must be finite: " + levelCm);
        }
    }
}
