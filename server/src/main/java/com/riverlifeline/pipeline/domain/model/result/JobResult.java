package com.riverlifeline.pipeline.domain.model.result;

import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import java.util.Map;

/** What a worker posts for a job (contract {@code JobResultData}). */
public sealed interface JobResult
        permits PassAssessment, ChangeResult, Classification, TrainedDroughtModel, DroughtForecast {
    /** The rasters the result declares; each one must have been uploaded under its name. */
    Map<ResultRasterName, RasterMeta> rasters();
}
