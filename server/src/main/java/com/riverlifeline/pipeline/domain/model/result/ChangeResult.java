package com.riverlifeline.pipeline.domain.model.result;

import com.riverlifeline.pipeline.domain.model.pass.PassId;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import java.util.Map;
import java.util.Objects;

/** Result of {@code detect_change}: water lost and gained since an earlier pass of the same track. */
public record ChangeResult(
        PassId passId, PassId sincePassId, double lostKm2, double gainedKm2, Map<ResultRasterName, RasterMeta> rasters)
        implements JobResult {
    public ChangeResult {
        Objects.requireNonNull(passId, "passId");
        Objects.requireNonNull(sincePassId, "sincePassId");
        rasters = Map.copyOf(rasters);
    }
}
