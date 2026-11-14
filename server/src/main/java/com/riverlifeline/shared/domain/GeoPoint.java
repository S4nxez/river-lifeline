package com.riverlifeline.shared.domain;

/** A WGS84 (EPSG:4326) position. Written as {@code [lon, lat]} in every JSON, GeoJSON order. */
public record GeoPoint(double lon, double lat) {
    public GeoPoint {
        if (!Double.isFinite(lon) || lon < -180 || lon > 180) {
            throw new IllegalArgumentException("Longitude out of range: " + lon);
        }
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) {
            throw new IllegalArgumentException("Latitude out of range: " + lat);
        }
    }
}
