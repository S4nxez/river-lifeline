package com.riverlifeline.monitoring.domain.model.status;

import com.riverlifeline.shared.domain.GeoPoint;
import java.util.List;

/** A simplified water route from the boarding point to the reference port (a GeoJSON LineString). */
public record Route(List<GeoPoint> coordinates) {
    public Route {
        coordinates = List.copyOf(coordinates);
        if (coordinates.size() < 2) {
            throw new IllegalArgumentException("A route needs at least 2 vertices");
        }
    }
}
