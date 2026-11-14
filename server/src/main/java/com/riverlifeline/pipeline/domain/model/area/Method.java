package com.riverlifeline.pipeline.domain.model.area;

import java.util.List;
import java.util.Objects;

/**
 * Parameters of the worker's method, from the area file. The backend only carries them to the worker; it reads
 * {@code sentinel1.maxDays} to know which scenes and gauge days a pass needs.
 */
public record Method(
        double accessRadiusM,
        double minCoverage,
        double minCoverageShown,
        double validFloorDb,
        double edgeMarginM,
        double waterDb,
        Double waterDbWeak,
        AdaptiveThreshold waterDbAdaptive,
        Speckle speckle,
        List<Double> widthsM,
        double safeWidthM,
        VegetatedWater vegetatedWater,
        LowWater lowWater,
        Sentinel1Method sentinel1) {

    public Method {
        Objects.requireNonNull(speckle, "speckle");
        widthsM = List.copyOf(widthsM);
        if (widthsM.isEmpty()) {
            throw new IllegalArgumentException("widths_m needs at least one width");
        }
    }

    /** Days around a pass the gauge must cover: the Sentinel-1 window plus one. */
    public int gaugeDays() {
        return (sentinel1 == null ? 0 : sentinel1.maxDays()) + 1;
    }

    public record AdaptiveThreshold(double rangeDbLow, double rangeDbHigh, double tileM, int minTiles) {}

    public record Speckle(String filter, Double looks, Double windowM) {
        public Speckle {
            if (!"refined_lee".equals(filter) && !"box".equals(filter)) {
                throw new IllegalArgumentException("Unknown speckle filter: " + filter);
            }
        }
    }

    public record VegetatedWater(double hhMinDb, double ratioMinDb, double nearWaterM) {}

    public record LowWater(
            double gaugeMaxCm,
            double seasonalPctLow,
            double seasonalPctHigh,
            double patchRadiusM,
            double vetoRadiusM) {}

    public record Sentinel1Method(int maxDays, double levelToleranceCm, double waterDb, Double weakDb, double landDb) {
        public Sentinel1Method {
            if (maxDays < 0) {
                throw new IllegalArgumentException("sentinel1.max_days must not be negative: " + maxDays);
            }
        }
    }
}
