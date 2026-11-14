package com.riverlifeline.monitoring.domain.model.status;

import com.riverlifeline.shared.domain.GeoPoint;
import java.util.Objects;

/** The narrowest stretch of the best route, and its width class. */
public record Bottleneck(GeoPoint location, Integer widthClassM) {
    public Bottleneck {
        Objects.requireNonNull(location, "bottleneck location");
    }
}
