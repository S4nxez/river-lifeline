package com.riverlifeline.pipeline.domain.model.area;

/** Outer edges of a lon/lat box, in degrees. */
public record Bbox(double lon0, double lat0, double lon1, double lat1) {
    /** Metres per degree of longitude at the equator and per degree of latitude (equirectangular). */
    public static final double M_PER_DEG_LON_EQUATOR = 111_320.0;

    public static final double M_PER_DEG_LAT = 110_570.0;

    public Bbox {
        if (!(lon0 < lon1) || !(lat0 < lat1)) {
            throw new IllegalArgumentException("Empty bbox: " + lon0 + ", " + lat0 + ", " + lon1 + ", " + lat1);
        }
    }

    /** The square around a circle, rounded to 1e-5 degrees. */
    public static Bbox around(Circle circle) {
        double r = circle.radiusKm() * 1000.0;
        double dlat = r / M_PER_DEG_LAT;
        double dlon = r / (M_PER_DEG_LON_EQUATOR * Math.cos(Math.toRadians(circle.lat())));
        return new Bbox(
                round5(circle.lon() - dlon),
                round5(circle.lat() - dlat),
                round5(circle.lon() + dlon),
                round5(circle.lat() + dlat));
    }

    private static double round5(double value) {
        return Math.round(value * 1e5) / 1e5;
    }
}
