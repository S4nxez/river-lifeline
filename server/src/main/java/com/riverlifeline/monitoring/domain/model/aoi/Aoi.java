package com.riverlifeline.monitoring.domain.model.aoi;

import com.riverlifeline.shared.domain.BoundingBox;
import com.riverlifeline.shared.domain.GeoPoint;
import java.util.Objects;

/** An area of interest: a circle around a reference town whose port the communities must reach by water. */
public record Aoi(
        AoiId id,
        String name,
        String countryCode,
        String region,
        GeoPoint center,
        double radiusKm,
        BoundingBox bbox,
        String timezone,
        Destination destination,
        RiverGauge riverGauge,
        AoiParameters parameters) {
    public Aoi {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(bbox, "bbox");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(parameters, "parameters");
    }
}
