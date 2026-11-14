package com.riverlifeline.pipeline.domain.model.result;

import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/** Result of {@code forecast_drought}. */
public record DroughtForecast(String region, int horizonDays, LocalDate asOf, double probability, String riskLevel)
        implements JobResult {
    public DroughtForecast {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(asOf, "asOf");
        if (!(probability >= 0 && probability <= 1)) {
            throw new IllegalArgumentException("probability must be within [0, 1]: " + probability);
        }
        if (!"low".equals(riskLevel) && !"medium".equals(riskLevel) && !"high".equals(riskLevel)) {
            throw new IllegalArgumentException("Unknown risk level: " + riskLevel);
        }
    }

    @Override
    public Map<ResultRasterName, RasterMeta> rasters() {
        return Map.of();
    }
}
