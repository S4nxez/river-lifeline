package com.riverlifeline.pipeline.domain.model.area;

import java.util.regex.Pattern;

/** A study area, e.g. {@code tefe}: the name of its file in the backend's {@code areas/}. */
public record AreaId(String value) {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,50}");

    public AreaId {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid area id: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
