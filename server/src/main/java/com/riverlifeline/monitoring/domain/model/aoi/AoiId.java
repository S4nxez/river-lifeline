package com.riverlifeline.monitoring.domain.model.aoi;

import com.riverlifeline.monitoring.domain.model.Identifier;

/** An area of interest, e.g. {@code tefe}. */
public record AoiId(String value) {
    public AoiId {
        Identifier.require(value, "AOI");
    }

    public static AoiId of(String value) {
        return new AoiId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
