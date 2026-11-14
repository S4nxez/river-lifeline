package com.riverlifeline.pipeline.domain.model.area;

import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import com.riverlifeline.pipeline.domain.model.raster.XY;
import java.util.Objects;

/**
 * The area's fixed lon/lat grid (EPSG:4326), the same for every pass: every area-grid raster the worker receives or
 * returns uses exactly this grid.
 */
public record AreaGrid(int width, int height, XY origin, XY step, double pixelM, Bbox bbox, Circle circle) {
    public static final String CRS = "EPSG:4326";

    public AreaGrid {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Empty grid: " + width + " x " + height);
        }
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(bbox, "bbox");
    }

    /**
     * The grid of an area file: for a circle the bbox is the square around it; {@code width = round((lon1 − lon0) /
     * step)}, {@code height = round((lat1 − lat0) / step)}, origin at the centre of the north-west pixel and
     * {@code pixelM} the geometric mean of a pixel's width and height in metres.
     */
    public static AreaGrid of(Bbox box, Circle circle, double stepDeg) {
        if (!(stepDeg > 0)) {
            throw new IllegalArgumentException("step_deg must be positive: " + stepDeg);
        }
        Bbox bbox = circle != null ? Bbox.around(circle) : Objects.requireNonNull(box, "bbox or circle");
        int width = (int) Math.round((bbox.lon1() - bbox.lon0()) / stepDeg);
        int height = (int) Math.round((bbox.lat1() - bbox.lat0()) / stepDeg);
        double midLat = Math.toRadians((bbox.lat0() + bbox.lat1()) / 2);
        double pixelM = stepDeg * Bbox.M_PER_DEG_LON_EQUATOR * Math.sqrt(Math.cos(midLat));
        XY origin = new XY(bbox.lon0() + stepDeg / 2, bbox.lat1() - stepDeg / 2);
        return new AreaGrid(width, height, origin, new XY(stepDeg, -stepDeg), pixelM, bbox, circle);
    }

    /** The layout of a raster on this grid. */
    public RasterMeta meta(DataType dtype, NoData nodata, RasterUnit unit) {
        return new RasterMeta(width, height, dtype, nodata, unit, CRS, origin, step);
    }

    /** Largest body a worker may upload for this area: a float32 raster of the whole grid. */
    public long maxRasterBytes() {
        return (long) width * height * DataType.FLOAT32.bytes();
    }
}
