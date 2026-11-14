package com.riverlifeline.pipeline.domain.model.raster;

import java.util.Objects;

/** A stored raster a job references: the worker downloads it from {@link #href()}. */
public record RasterRef(RasterId id, RasterMeta meta) {
    public static final String HREF_PREFIX = "/internal/worker/rasters/";

    public RasterRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(meta, "meta");
    }

    public String href() {
        return HREF_PREFIX + id.value();
    }
}
