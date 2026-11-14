package com.riverlifeline.pipeline.domain.model.area;

/** A circular area: only the pixels within it belong to the area. */
public record Circle(double lon, double lat, double radiusKm) {
    public Circle {
        if (!(radiusKm > 0)) {
            throw new IllegalArgumentException("A circle needs a positive radius: " + radiusKm);
        }
    }
}
