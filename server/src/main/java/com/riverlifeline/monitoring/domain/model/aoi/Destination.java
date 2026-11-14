package com.riverlifeline.monitoring.domain.model.aoi;

import com.riverlifeline.shared.domain.GeoPoint;
import java.util.Objects;

/** The reference port: "connected" means a boat can reach it. */
public record Destination(String name, GeoPoint location, String osmId) {
    public Destination {
        Objects.requireNonNull(name, "destination name");
        Objects.requireNonNull(location, "destination location");
    }
}
