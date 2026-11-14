package com.riverlifeline.pipeline.domain.model.raster;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Layout of a raw raster body: {@code width × height} values of {@code dtype}, little-endian, row-major, row 0 first.
 * {@code origin} is the centre of pixel (0, 0) in {@code crs}; pixel (r, c) is centred on
 * {@code (origin.x + c × step.x, origin.y + r × step.y)}.
 */
public record RasterMeta(
        int width, int height, DataType dtype, NoData nodata, RasterUnit unit, String crs, XY origin, XY step) {
    private static final Pattern CRS = Pattern.compile("EPSG:[0-9]+");

    public RasterMeta {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("A raster has at least one pixel: " + width + " x " + height);
        }
        Objects.requireNonNull(dtype, "dtype");
        Objects.requireNonNull(nodata, "nodata");
        Objects.requireNonNull(unit, "unit");
        if (crs == null || !CRS.matcher(crs).matches()) {
            throw new IllegalArgumentException("Invalid crs: " + crs);
        }
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(step, "step");
        if (nodata instanceof NoData.NotANumber && dtype != DataType.FLOAT32) {
            throw new IllegalArgumentException("NaN nodata needs a float raster");
        }
    }

    /** Exact length of the raw body. */
    public long byteLength() {
        return (long) width * height * dtype.bytes();
    }

    /** Same grid (size, CRS, origin and step) as another raster, whatever the values. */
    public boolean sameGridAs(RasterMeta other) {
        return width == other.width
                && height == other.height
                && crs.equals(other.crs)
                && close(origin.x(), other.origin.x(), step.x())
                && close(origin.y(), other.origin.y(), step.y())
                && close(step.x(), other.step.x(), step.x())
                && close(step.y(), other.step.y(), step.y());
    }

    private static boolean close(double a, double b, double scale) {
        return Math.abs(a - b) <= Math.abs(scale) * 1e-6;
    }
}
