package com.riverlifeline.monitoring.domain.model.pass;

import com.riverlifeline.shared.domain.BoundingBox;
import java.util.Objects;

/** A styled PNG of one layer of a pass; {@code path} is relative to the AOI folder. */
public record LayerImage(LayerId id, String path, BoundingBox bounds, Integer widthPx, Integer heightPx) {
    public LayerImage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bounds, "bounds");
    }
}
