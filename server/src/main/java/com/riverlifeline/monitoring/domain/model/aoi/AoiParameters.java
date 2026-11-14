package com.riverlifeline.monitoring.domain.model.aoi;

import java.util.List;

/** The processing parameters of an AOI, as written in the catalog. */
public record AoiParameters(
        List<Integer> testedWidthsM,
        int connectedMinWidthM,
        int monitorMinWidthM,
        double minCoverageFraction,
        double lowWaterThresholdM,
        double waterThresholdDb,
        double waterHysteresisDb,
        double fillValueDb,
        int swathEdgeBufferM) {
    public AoiParameters {
        testedWidthsM = List.copyOf(testedWidthsM);
    }
}
