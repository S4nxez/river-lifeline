package com.riverlifeline.pipeline.domain.model.pass;

import java.time.LocalDate;
import java.util.regex.Pattern;

/** A NISAR pass over an area: every frame of one track on one day, e.g. {@code 2026-10-23_t161A}. */
public record PassId(String value) {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    public PassId {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid pass id: " + value);
        }
    }

    public static PassId of(LocalDate day, int track, Direction direction) {
        return new PassId(String.format("%s_t%03d%s", day, track, direction.letter()));
    }

    @Override
    public String toString() {
        return value;
    }
}
