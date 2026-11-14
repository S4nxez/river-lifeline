package com.riverlifeline.pipeline.domain.model.area;

import com.riverlifeline.shared.domain.GeoPoint;
import java.util.Objects;

/** A place routes are measured to (the hub town, a point on the main river): water within radiusM reaches it. */
public record Target(String name, GeoPoint position, double radiusM, GeoPoint access) {
    public Target {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(position, "position");
        if (!(radiusM > 0)) {
            throw new IllegalArgumentException("radius_m must be positive: " + radiusM);
        }
    }
}
