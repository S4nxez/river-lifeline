package com.riverlifeline.monitoring.domain.model.community;

import com.riverlifeline.shared.domain.GeoPoint;
import java.util.Objects;

/** A riverside community ({@code communities.geojson}). Its status is measured from its boarding point. */
public record Community(
        CommunityId id,
        String name,
        CommunityKind kind,
        String municipality,
        String osmRef,
        Integer population,
        boolean monitored,
        ExclusionReason exclusionReason,
        GeoPoint villageLocation,
        BoardingPoint boardingPoint,
        Integer referenceMaxWidthClassM) {

    public Community {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(villageLocation, "villageLocation");
    }

    /** Where the measurement is made: the boarding point, or the village centre when there is none. */
    public GeoPoint measurementPoint() {
        return boardingPoint != null ? boardingPoint.location() : villageLocation;
    }
}
