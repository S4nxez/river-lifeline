package com.riverlifeline.shared.domain;

/** A WGS84 bounding box, written as {@code [west, south, east, north]}. */
public record BoundingBox(double west, double south, double east, double north) {
    public BoundingBox {
        new GeoPoint(west, south);
        new GeoPoint(east, north);
        if (west > east || south > north) {
            throw new IllegalArgumentException(
                    "Bounding box corners are swapped: [" + west + ", " + south + ", " + east + ", " + north + "]");
        }
    }
}
