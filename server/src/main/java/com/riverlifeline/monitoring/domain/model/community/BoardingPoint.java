package com.riverlifeline.monitoring.domain.model.community;

import com.riverlifeline.shared.domain.GeoPoint;
import java.util.Objects;

/** The pier, port or ferry terminal the route is measured from. {@code source} is null if it was not recognised. */
public record BoardingPoint(GeoPoint location, BoardingPointSource source, String osmRef, Integer distanceFromCenterM) {
    public BoardingPoint {
        Objects.requireNonNull(location, "boarding point location");
    }
}
