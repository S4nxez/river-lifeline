package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The rows of the pipeline's tables (V5) the public API reads, as plain values. */
public final class PipelineRows {
    private PipelineRows() {}

    /**
     * A shown, assessed pass ({@code radar_pass}) with what hangs from it.
     *
     * @param counts null until classify_communities has run for the pass
     * @param sentinel1Dates the Sentinel-1 scene dates the pass was judged with; empty without them
     * @param change the change against the previous shown pass of its track, or null
     */
    public record PassRow(
            String passId,
            LocalDate date,
            String timeUtc,
            int track,
            String direction,
            String granulesJson,
            Instant assessedAt,
            String methodDigest,
            double coverage,
            boolean evaluated,
            double waterKm2,
            Double vegetatedKm2,
            Double riverLevelCm,
            Boolean lowWater,
            Double spacingM,
            RasterBox water,
            RasterBox radar,
            CountsRow counts,
            List<LocalDate> sentinel1Dates,
            ChangeRow change) {}

    /** {@code pass_counts}: counts over monitored communities, and the unconfirmed isolations. */
    public record CountsRow(int connected, int atRisk, int isolated, int noData, List<String> unconfirmed) {}

    /** {@code pass_change}: water lost and gained since an earlier pass. */
    public record ChangeRow(String sincePassId, double lostKm2, double gainedKm2, RasterBox raster) {}

    /** {@code connectivity_assessment}: a community's route on a pass. */
    public record AssessmentRow(
            String passId,
            String communityId,
            String status,
            Integer bottleneckM,
            Integer distanceToWaterM,
            boolean unverifiedRoute) {}

    /** {@code gauge_day}: the daily median level at a gauge. */
    public record GaugeRow(LocalDate day, double levelCm) {}

    /**
     * A stored result raster ({@code raster}) and its layout: {@code width x height} pixels, {@code origin} the centre
     * of the first one, {@code step} signed.
     */
    public record RasterBox(
            String id,
            int width,
            int height,
            String dtype,
            double originX,
            double originY,
            double stepX,
            double stepY) {
        public double west() {
            return originX - stepX / 2;
        }

        public double north() {
            return originY - stepY / 2;
        }

        public double east() {
            return west() + width * stepX;
        }

        public double south() {
            return north() + height * stepY;
        }
    }
}
