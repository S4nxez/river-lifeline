package com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The JSON bodies of contract 2 (CONTRACTS.md 3.3). Components are camelCase here and snake_case on the wire (the
 * application's Jackson naming strategy); nulls are always written. Coordinates are {@code [lon, lat]} lists.
 */
public final class ApiResponses {
    private ApiResponses() {}

    public record ListResponse<T>(List<T> items, int count) {
        public static <T> ListResponse<T> of(List<T> items) {
            return new ListResponse<>(items, items.size());
        }
    }

    // --- #1 health, #2 meta -------------------------------------------------------------------------------------

    public record HealthResponse(String status, String apiVersion, CatalogStateResponse catalog) {}

    public record CatalogStateResponse(boolean loaded, String schemaVersion, Instant generatedAt) {}

    public record MetaResponse(
            String aoiId,
            String apiVersion,
            String schemaVersion,
            String methodVersion,
            List<StatusDefinitionResponse> statusTable,
            List<ReasonResponse> reasons,
            List<LayerInfoResponse> layers) {}

    public record StatusDefinitionResponse(
            String code, String label, String color, String shape, int order, String description) {}

    public record ReasonResponse(String code, String status, String label) {}

    public record LayerInfoResponse(String id, String label, boolean defaultVisible, double defaultOpacity) {}

    // --- #3 aois, #4 aoi, #5 catalog-version, #6 summary --------------------------------------------------------

    public record AoiListItemResponse(
            String id, String name, List<Double> center, Number radiusKm, List<Double> bbox) {}

    public record AoiResponse(
            String id,
            String name,
            String countryCode,
            String region,
            List<Double> center,
            Number radiusKm,
            List<Double> bbox,
            String timezone,
            DestinationResponse destination,
            RiverGaugeResponse riverGauge,
            ParametersResponse parameters,
            int passCount,
            int evaluatedPassCount,
            Instant firstPassAt,
            Instant latestPassAt) {}

    public record DestinationResponse(String name, List<Double> location, String osmId) {}

    public record RiverGaugeResponse(String provider, String stationCode, String stationName) {}

    public record ParametersResponse(
            List<Integer> testedWidthsM,
            int connectedMinWidthM,
            int monitorMinWidthM,
            double minCoverageFraction,
            double lowWaterThresholdM,
            double waterThresholdDb,
            double waterHysteresisDb,
            double fillValueDb,
            int swathEdgeBufferM) {}

    public record CatalogVersionResponse(
            String aoiId,
            String schemaVersion,
            Instant generatedAt,
            String etag,
            String latestPassId,
            String latestEvaluatedPassId) {}

    public record SummaryResponse(
            String aoiId,
            LocalDate date,
            String passId,
            Instant acquiredAt,
            String headline,
            String headlineLevel,
            CountsResponse counts,
            Double riverLevelM,
            Boolean lowWaterRuleActive,
            Instant catalogGeneratedAt) {}

    public record CountsResponse(
            Integer monitored, Integer connected, Integer atRisk, Integer isolated, Integer noData, Integer changed) {}

    // --- #7 passes, #8 pass -------------------------------------------------------------------------------------

    public record PassResponse(
            String id,
            Instant acquiredAt,
            String platform,
            String band,
            Integer track,
            Integer frame,
            String orbitDirection,
            List<String> polarizations,
            Number pixelSizeM,
            Double coverageFraction,
            boolean evaluated,
            Double riverLevelM,
            boolean lowWaterRuleActive,
            CountsResponse counts,
            String previousPassId,
            String nextPassId) {}

    public record PassDetailResponse(
            @JsonUnwrapped PassResponse pass,
            ImageCardResponse imageCard,
            LowWaterRuleResponse lowWaterRule,
            String methodVersion,
            Instant processedAt,
            List<LayerResponse> layers) {}

    public record ImageCardResponse(
            String satellite,
            String sensor,
            String band,
            Number wavelengthCm,
            String product,
            String granuleId,
            Integer track,
            Integer frame,
            Integer absoluteOrbit,
            String orbitDirection,
            Instant acquiredAt,
            Number pixelSizeM,
            List<String> polarizations,
            String dataSourceUrl) {}

    public record LowWaterRuleResponse(boolean active, List<String> s1SceneIds, Double unverifiedAreaKm2) {}

    public record LayerResponse(String id, String url, List<Double> bounds, Integer widthPx, Integer heightPx) {}

    // --- #9 communities on a pass, #13 GeoJSON export -----------------------------------------------------------

    public record PassCommunitiesResponse(
            String type,
            String aoiId,
            String passId,
            Instant acquiredAt,
            int count,
            List<CommunityFeatureResponse> features) {}

    public record GeoJsonExportResponse(@JsonUnwrapped PassCommunitiesResponse collection, String attribution) {}

    /** The geometry is the boarding point, where the measurement is made; the village is in the properties. */
    public record CommunityFeatureResponse(
            String type, String id, PointResponse geometry, CommunityPropertiesResponse properties) {}

    public record PointResponse(String type, List<Double> coordinates) {}

    public record CommunityPropertiesResponse(
            String id,
            String name,
            String kind,
            String municipality,
            String status,
            String reason,
            Integer minWidthClassM,
            boolean routeVerified,
            String previousStatus,
            boolean changed,
            List<Double> villageLocation,
            BoardingPointSummaryResponse boardingPoint,
            BottleneckResponse bottleneck) {}

    public record BoardingPointSummaryResponse(List<Double> location, String source, Integer distanceFromCenterM) {}

    public record BottleneckResponse(List<Double> location, Integer widthClassM) {}

    // --- #10 community --------------------------------------------------------------------------------------------

    public record CommunityResponse(
            String id,
            String name,
            String kind,
            String municipality,
            String osmRef,
            boolean monitored,
            String exclusionReason,
            List<Double> villageLocation,
            BoardingPointResponse boardingPoint,
            Integer referenceMaxWidthClassM,
            CurrentStatusResponse current,
            List<HistoryEntryResponse> history) {}

    public record BoardingPointResponse(
            List<Double> location, String source, String osmRef, Integer distanceFromCenterM) {}

    public record CurrentStatusResponse(
            String passId,
            Instant acquiredAt,
            String status,
            String reason,
            Integer minWidthClassM,
            boolean routeVerified,
            BottleneckResponse bottleneck,
            LineStringResponse route) {}

    public record LineStringResponse(String type, List<List<Double>> coordinates) {}

    public record HistoryEntryResponse(
            String passId,
            Instant acquiredAt,
            String status,
            String reason,
            Integer minWidthClassM,
            Double riverLevelM) {}

    // --- #11 river level ------------------------------------------------------------------------------------------

    public record RiverLevelResponse(
            String provider,
            String stationCode,
            String stationName,
            String unit,
            Double lowWaterThresholdM,
            List<RiverLevelItemResponse> items,
            int count) {}

    public record RiverLevelItemResponse(LocalDate date, double levelM) {}

    // --- errors (CONTRACTS.md 3.2) --------------------------------------------------------------------------------

    public record ErrorResponse(ErrorBody error) {
        public static ErrorResponse of(String code, String message, Object details) {
            return new ErrorResponse(new ErrorBody(code, message, details));
        }
    }

    public record ErrorBody(String code, String message, Object details) {}
}
