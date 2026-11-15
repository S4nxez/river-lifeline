package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import com.riverlifeline.shared.domain.BoundingBox;
import com.riverlifeline.shared.domain.GeoPoint;
import java.util.List;
import java.util.Objects;

/**
 * What the public API needs of an area file ({@code areas/<id>.json} and its communities), read on its own: the
 * monitoring context never uses the pipeline's classes.
 *
 * @param circle the circle of a circular area, or null for a box
 * @param bbox the outer edges of the area's grid (for a circle, the square around it)
 */
public record AreaFile(
        String id,
        String name,
        String country,
        Circle circle,
        BoundingBox bbox,
        Place hub,
        Gauge gauge,
        Method method,
        List<CommunityPlace> communities) {

    /** Metres per degree, as the pipeline derives the grid of a circular area. */
    static final double M_PER_DEG_LON_EQUATOR = 111_320.0;

    static final double M_PER_DEG_LAT = 110_570.0;

    public AreaFile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(bbox, "bbox");
        Objects.requireNonNull(hub, "hub");
        Objects.requireNonNull(method, "method");
        communities = List.copyOf(communities);
    }

    /** The centre of the circle, or of the box. */
    public GeoPoint center() {
        return circle != null
                ? new GeoPoint(circle.lon(), circle.lat())
                : new GeoPoint((bbox.west() + bbox.east()) / 2, (bbox.south() + bbox.north()) / 2);
    }

    /** The circle's radius, or half the box's longest side, in km. */
    public double radiusKm() {
        if (circle != null) {
            return circle.radiusKm();
        }
        double k = Math.cos(Math.toRadians(center().lat()));
        double halfWidth = (bbox.east() - bbox.west()) / 2 * M_PER_DEG_LON_EQUATOR * k;
        double halfHeight = (bbox.north() - bbox.south()) / 2 * M_PER_DEG_LAT;
        return Math.max(halfWidth, halfHeight) / 1000.0;
    }

    /** Whether a point belongs to the area: inside the circle, or inside the box. */
    public boolean contains(double lon, double lat) {
        if (circle == null) {
            return lon >= bbox.west() && lon <= bbox.east() && lat >= bbox.south() && lat <= bbox.north();
        }
        double x = (lon - circle.lon()) * M_PER_DEG_LON_EQUATOR * Math.cos(Math.toRadians(circle.lat()));
        double y = (lat - circle.lat()) * M_PER_DEG_LAT;
        double r = circle.radiusKm() * 1000.0;
        return x * x + y * y <= r * r;
    }

    /** The square around a circle, rounded to 1e-5 degrees, as the pipeline derives it. */
    static BoundingBox around(Circle circle) {
        double r = circle.radiusKm() * 1000.0;
        double dlat = r / M_PER_DEG_LAT;
        double dlon = r / (M_PER_DEG_LON_EQUATOR * Math.cos(Math.toRadians(circle.lat())));
        return new BoundingBox(
                round5(circle.lon() - dlon),
                round5(circle.lat() - dlat),
                round5(circle.lon() + dlon),
                round5(circle.lat() + dlat));
    }

    private static double round5(double value) {
        return Math.round(value * 100_000.0) / 100_000.0;
    }

    public record Circle(double lon, double lat, double radiusKm) {}

    /** A named point; {@code access} is where people reach the water, when known. */
    public record Place(String name, GeoPoint location, GeoPoint access) {
        public GeoPoint waterAccess() {
            return access != null ? access : location;
        }
    }

    /** The river gauge of the area (ANA telemetry station). */
    public record Gauge(String stationCode, String name) {}

    /** The method parameters the public API shows. */
    public record Method(
            List<Integer> widthsM,
            int safeWidthM,
            double accessRadiusM,
            double minCoverage,
            double waterDb,
            Double waterDbWeak,
            double validFloorDb,
            double edgeMarginM,
            Double lowWaterGaugeMaxCm) {
        public Method {
            widthsM = List.copyOf(widthsM);
        }
    }

    /** A place of the communities file (OpenStreetMap). */
    public record CommunityPlace(String id, String name, String place, GeoPoint location, GeoPoint access) {}
}
