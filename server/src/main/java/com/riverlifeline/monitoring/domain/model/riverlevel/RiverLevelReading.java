package com.riverlifeline.monitoring.domain.model.riverlevel;

import java.time.LocalDate;
import java.util.Objects;

/** The river level of one day at the gauge. */
public record RiverLevelReading(LocalDate date, double levelM) {
    public RiverLevelReading {
        Objects.requireNonNull(date, "date");
        if (!Double.isFinite(levelM)) {
            throw new IllegalArgumentException("Level is not a number on " + date);
        }
    }
}
