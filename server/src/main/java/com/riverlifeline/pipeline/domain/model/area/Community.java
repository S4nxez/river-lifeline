package com.riverlifeline.pipeline.domain.model.area;

import com.riverlifeline.shared.domain.GeoPoint;
import java.util.Objects;

/** A riverside place of the area's communities file; {@code access} is where people reach the water, if known. */
public record Community(String id, String name, String place, GeoPoint position, GeoPoint access) {
    public Community {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A community needs an id");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(place, "place");
        Objects.requireNonNull(position, "position");
    }
}
