package com.riverlifeline.pipeline.domain.model.pass;

import java.time.LocalDate;
import java.util.Objects;

/** When and how a pass was acquired; {@code timeUtc} is {@code HH:mm}, the start of its first frame. */
public record PassInfo(PassId id, LocalDate date, String timeUtc, int track, Direction direction) {
    public PassInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(date, "date");
        if (timeUtc == null || !timeUtc.matches("[0-2][0-9]:[0-5][0-9]")) {
            throw new IllegalArgumentException("Invalid time: " + timeUtc);
        }
        Objects.requireNonNull(direction, "direction");
    }
}
