package com.riverlifeline.pipeline.domain.model.result;

import com.riverlifeline.pipeline.domain.model.pass.PassId;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Result of {@code assess_pass} and {@code reevaluate_pass}. */
public record PassAssessment(
        PassId passId,
        double coverage,
        boolean shown,
        boolean evaluated,
        double waterKm2,
        double narrowKm2,
        Double vegetatedKm2,
        Threshold threshold,
        Double riverLevelCm,
        Boolean lowWater,
        RouteResult hubLink,
        List<CommunityRoute> communities,
        List<String> unverifiedRoute,
        Sentinel1Agreement sentinel1,
        Stats stats,
        Map<ResultRasterName, RasterMeta> rasters)
        implements JobResult {

    public PassAssessment {
        Objects.requireNonNull(passId, "passId");
        Objects.requireNonNull(threshold, "threshold");
        communities = List.copyOf(communities);
        unverifiedRoute = List.copyOf(unverifiedRoute);
        rasters = Map.copyOf(rasters);
    }

    public record Threshold(String source, double strongDb, Double weakDb, Double measuredDb, Integer tiles) {
        public Threshold {
            if (!"fixed".equals(source) && !"adaptive".equals(source)) {
                throw new IllegalArgumentException("Unknown threshold source: " + source);
            }
        }
    }

    public record Sentinel1Agreement(
            List<LocalDate> dates,
            double judgedKm2,
            Double agreement,
            double bothWaterKm2,
            double nisarOnlyKm2,
            double sentinel1OnlyKm2,
            double mudKm2) {
        public Sentinel1Agreement {
            dates = List.copyOf(dates);
        }
    }

    /** Informative only. */
    public record Stats(Double seconds, Double spacingM) {}
}
