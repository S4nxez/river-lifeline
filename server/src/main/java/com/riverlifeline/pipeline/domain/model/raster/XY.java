package com.riverlifeline.pipeline.domain.model.raster;

/** A position or a signed pixel size in a raster's CRS units. */
public record XY(double x, double y) {
    public XY {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Coordinates must be finite: " + x + ", " + y);
        }
    }
}
