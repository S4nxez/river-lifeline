package com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The files of contract 1 (CONTRACTS.md section 2) exactly as written, before any validation. Field names map to the
 * snake_case keys of the files. {@link ProcessingMapper} turns them into the domain model.
 */
final class ProcessingDocuments {
    private ProcessingDocuments() {}

    // --- catalog.json -------------------------------------------------------------------------------------------

    record CatalogDocument(
            String schemaVersion,
            Instant generatedAt,
            AoiDocument aoi,
            List<StatusDefinitionDocument> statusTable,
            MethodDocument method,
            List<PassDocument> passes) {}

    record AoiDocument(
            String id,
            String name,
            String countryCode,
            String region,
            List<Double> center,
            Double radiusKm,
            List<Double> bbox,
            String timezone,
            DestinationDocument destination,
            RiverGaugeDocument riverGauge,
            ParametersDocument parameters) {}

    record DestinationDocument(String name, List<Double> location, String osmId) {}

    record RiverGaugeDocument(String provider, String stationCode, String stationName) {}

    record ParametersDocument(
            List<Integer> testedWidthsM,
            Integer connectedMinWidthM,
            Integer monitorMinWidthM,
            Double minCoverageFraction,
            Double lowWaterThresholdM,
            Double waterThresholdDb,
            Double waterHysteresisDb,
            Double fillValueDb,
            Integer swathEdgeBufferM) {}

    record StatusDefinitionDocument(
            String code, String label, String color, String shape, Integer order, String description) {}

    record MethodDocument(String version, String description) {}

    record PassDocument(
            String id,
            Instant acquiredAt,
            String platform,
            String band,
            Double wavelengthCm,
            String product,
            String granuleId,
            Integer track,
            Integer frame,
            Integer absoluteOrbit,
            String orbitDirection,
            List<String> polarizations,
            Double pixelSizeM,
            Double coverageFraction,
            Boolean evaluated,
            Double riverLevelM,
            LowWaterRuleDocument lowWaterRule,
            CountsDocument counts,
            Instant processedAt,
            String methodVersion,
            PassFilesDocument files) {}

    record LowWaterRuleDocument(Boolean active, List<String> s1SceneIds, Double unverifiedAreaKm2) {}

    record CountsDocument(
            Integer monitored, Integer connected, Integer atRisk, Integer isolated, Integer noData, Integer changed) {}

    record PassFilesDocument(String statuses, Map<String, LayerFileDocument> layers) {}

    record LayerFileDocument(String path, List<Double> bounds, Integer widthPx, Integer heightPx) {}

    // --- communities.geojson ------------------------------------------------------------------------------------

    record CommunitiesDocument(String schemaVersion, String aoiId, List<FeatureDocument> features) {}

    record FeatureDocument(String id, PointDocument geometry, CommunityPropertiesDocument properties) {}

    record PointDocument(String type, List<Double> coordinates) {}

    record CommunityPropertiesDocument(
            String id,
            String name,
            String kind,
            String municipality,
            String osmRef,
            Integer population,
            Boolean monitored,
            String exclusionReason,
            BoardingPointDocument boardingPoint,
            Integer referenceMaxWidthClassM) {}

    record BoardingPointDocument(List<Double> location, String source, String osmRef, Integer distanceFromCenterM) {}

    // --- passes/<pass_id>/statuses.json -------------------------------------------------------------------------

    record StatusesDocument(
            String schemaVersion,
            String aoiId,
            String passId,
            Instant acquiredAt,
            Boolean evaluated,
            List<CommunityStatusDocument> communities) {}

    record CommunityStatusDocument(
            String communityId,
            String status,
            String reason,
            Integer minWidthClassM,
            Boolean routeVerified,
            String previousStatus,
            String previousPassId,
            Boolean changed,
            BottleneckDocument bottleneck,
            LineStringDocument route) {}

    record BottleneckDocument(List<Double> location, Integer widthClassM) {}

    record LineStringDocument(String type, List<List<Double>> coordinates) {}

    // --- river_level.json ---------------------------------------------------------------------------------------

    record RiverLevelDocument(
            String schemaVersion,
            String aoiId,
            String provider,
            String stationCode,
            String stationName,
            String unit,
            List<RiverLevelPointDocument> series) {}

    record RiverLevelPointDocument(LocalDate date, Double levelM) {}
}
