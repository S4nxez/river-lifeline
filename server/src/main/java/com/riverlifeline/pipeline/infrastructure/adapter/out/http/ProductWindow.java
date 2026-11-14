package com.riverlifeline.pipeline.infrastructure.adapter.out.http;

import com.riverlifeline.pipeline.domain.model.area.Bbox;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * The rows and columns of a product's grid that cover an area: 50 points per side of the area's lon/lat bbox are
 * projected into the product's CRS, and the window holds them plus a padding of a few pixels.
 *
 * @param r0 first row
 * @param r1 row after the last
 * @param c0 first column
 * @param c1 column after the last
 */
public record ProductWindow(int r0, int r1, int c0, int c1) {
    private static final int EDGE_POINTS = 50;
    private static final CRSFactory CRS = new CRSFactory();
    private static final CoordinateReferenceSystem WGS84 = CRS.createFromName("EPSG:4326");
    private static final Map<Integer, CoordinateTransform> TRANSFORMS = new ConcurrentHashMap<>();

    public int width() {
        return Math.max(0, c1 - c0);
    }

    public int height() {
        return Math.max(0, r1 - r0);
    }

    /**
     * The window of a grid whose pixel (0, 0) is centred on (x0, y0) with signed steps (dx, dy) and {@code width ×
     * height} pixels; empty (zero width or height) when the area is outside it.
     */
    public static ProductWindow of(
            Bbox bbox, int epsg, double x0, double y0, double dx, double dy, int width, int height, int padPixels) {
        double[] range = projectedRange(bbox, epsg);
        double padX = padPixels * Math.abs(dx);
        double padY = padPixels * Math.abs(dy);
        double ca = (range[0] - padX - x0) / dx;
        double cb = (range[2] + padX - x0) / dx;
        double ra = (range[1] - padY - y0) / dy;
        double rb = (range[3] + padY - y0) / dy;
        int c0 = (int) Math.max(0, Math.floor(Math.min(ca, cb)));
        int c1 = (int) Math.min(width, Math.ceil(Math.max(ca, cb)) + 1);
        int r0 = (int) Math.max(0, Math.floor(Math.min(ra, rb)));
        int r1 = (int) Math.min(height, Math.ceil(Math.max(ra, rb)) + 1);
        return new ProductWindow(r0, Math.max(r0, r1), c0, Math.max(c0, c1));
    }

    /** {minX, minY, maxX, maxY} of the bbox's edge in the target CRS. */
    static double[] projectedRange(Bbox bbox, int epsg) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        if (epsg == 4326) {
            return new double[] {bbox.lon0(), bbox.lat0(), bbox.lon1(), bbox.lat1()};
        }
        CoordinateTransform transform = TRANSFORMS.computeIfAbsent(
                epsg,
                code -> new CoordinateTransformFactory().createTransform(WGS84, CRS.createFromName("EPSG:" + code)));
        ProjCoordinate out = new ProjCoordinate();
        for (int side = 0; side < 4; side++) {
            for (int i = 0; i < EDGE_POINTS; i++) {
                double t = i / (double) (EDGE_POINTS - 1);
                double lon = switch (side) {
                    case 0 -> bbox.lon0() + t * (bbox.lon1() - bbox.lon0());
                    case 1 -> bbox.lon1();
                    case 2 -> bbox.lon1() - t * (bbox.lon1() - bbox.lon0());
                    default -> bbox.lon0();
                };
                double lat = switch (side) {
                    case 0 -> bbox.lat0();
                    case 1 -> bbox.lat0() + t * (bbox.lat1() - bbox.lat0());
                    case 2 -> bbox.lat1();
                    default -> bbox.lat1() - t * (bbox.lat1() - bbox.lat0());
                };
                synchronized (transform) {
                    transform.transform(new ProjCoordinate(lon, lat), out);
                }
                minX = Math.min(minX, out.x);
                maxX = Math.max(maxX, out.x);
                minY = Math.min(minY, out.y);
                maxY = Math.max(maxY, out.y);
            }
        }
        return new double[] {minX, minY, maxX, maxY};
    }
}
