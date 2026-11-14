package com.riverlifeline.pipeline.domain.model.result;

import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import com.riverlifeline.pipeline.domain.model.source.DroughtModel;
import java.util.Map;
import java.util.Objects;

/** Result of {@code train_drought_model}. */
public record TrainedDroughtModel(String region, int observations, int droughts, DroughtModel model)
        implements JobResult {
    public TrainedDroughtModel {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(model, "model");
    }

    @Override
    public Map<ResultRasterName, RasterMeta> rasters() {
        return Map.of();
    }
}
