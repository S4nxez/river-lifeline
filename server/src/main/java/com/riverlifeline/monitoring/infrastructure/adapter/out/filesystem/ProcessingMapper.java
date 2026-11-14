package com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem;

import com.riverlifeline.monitoring.domain.model.aoi.Aoi;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.aoi.AoiParameters;
import com.riverlifeline.monitoring.domain.model.aoi.Destination;
import com.riverlifeline.monitoring.domain.model.aoi.RiverGauge;
import com.riverlifeline.monitoring.domain.model.catalog.Catalog;
import com.riverlifeline.monitoring.domain.model.catalog.MethodInfo;
import com.riverlifeline.monitoring.domain.model.community.BoardingPoint;
import com.riverlifeline.monitoring.domain.model.community.BoardingPointSource;
import com.riverlifeline.monitoring.domain.model.community.Community;
import com.riverlifeline.monitoring.domain.model.community.CommunityId;
import com.riverlifeline.monitoring.domain.model.community.CommunityKind;
import com.riverlifeline.monitoring.domain.model.community.ExclusionReason;
import com.riverlifeline.monitoring.domain.model.pass.LayerId;
import com.riverlifeline.monitoring.domain.model.pass.LayerImage;
import com.riverlifeline.monitoring.domain.model.pass.LowWaterRule;
import com.riverlifeline.monitoring.domain.model.pass.OrbitDirection;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.pass.PassId;
import com.riverlifeline.monitoring.domain.model.pass.StatusCounts;
import com.riverlifeline.monitoring.domain.model.riverlevel.RiverLevelReading;
import com.riverlifeline.monitoring.domain.model.riverlevel.RiverLevelSeries;
import com.riverlifeline.monitoring.domain.model.status.Bottleneck;
import com.riverlifeline.monitoring.domain.model.status.CommunityAssessment;
import com.riverlifeline.monitoring.domain.model.status.PassStatuses;
import com.riverlifeline.monitoring.domain.model.status.Reason;
import com.riverlifeline.monitoring.domain.model.status.Route;
import com.riverlifeline.monitoring.domain.model.status.Status;
import com.riverlifeline.monitoring.domain.model.status.StatusDefinition;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.AoiDocument;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.CatalogDocument;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.CommunitiesDocument;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.CommunityStatusDocument;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.CountsDocument;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.FeatureDocument;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.LayerFileDocument;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.LineStringDocument;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.ParametersDocument;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.PassDocument;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.RiverLevelDocument;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.StatusDefinitionDocument;
import com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem.ProcessingDocuments.StatusesDocument;
import com.riverlifeline.shared.domain.BoundingBox;
import com.riverlifeline.shared.domain.GeoPoint;
import com.riverlifeline.shared.domain.SchemaVersion;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the documents of contract 1 into the domain model. A malformed catalog fails as a whole; a single malformed
 * community or status entry is skipped with a warning, which only ever shows less (the community then has no data).
 */
final class ProcessingMapper {
    private static final Logger log = LoggerFactory.getLogger(ProcessingMapper.class);

    private ProcessingMapper() {}

    // --- catalog.json -------------------------------------------------------------------------------------------

    static Catalog toCatalog(AoiId folderId, SchemaVersion schemaVersion, CatalogDocument document) {
        AoiDocument aoiDocument = required(document.aoi(), "aoi");
        Aoi aoi = toAoi(aoiDocument);
        if (!aoi.id().equals(folderId)) {
            throw new IllegalArgumentException("aoi.id '" + aoi.id() + "' doesn't match its folder '" + folderId + "'");
        }
        List<StatusDefinition> statusTable = new ArrayList<>();
        for (StatusDefinitionDocument row : orEmpty(document.statusTable())) {
            Status.fromCode(row.code())
                    .ifPresentOrElse(
                            status -> statusTable.add(new StatusDefinition(
                                    status,
                                    row.label(),
                                    row.color(),
                                    row.shape(),
                                    row.order() == null ? status.defaultOrder() : row.order(),
                                    row.description())),
                            () -> log.warn(
                                    "Ignoring unknown status '{}' in the status table of {}", row.code(), folderId));
        }
        MethodInfo method = document.method() == null
                ? null
                : new MethodInfo(document.method().version(), document.method().description());
        List<Pass> passes = orEmpty(document.passes()).stream()
                .map(pass -> toPass(pass, aoi.bbox()))
                .toList();
        return new Catalog(schemaVersion, document.generatedAt(), aoi, statusTable, method, passes);
    }

    private static Aoi toAoi(AoiDocument document) {
        ParametersDocument parameters = required(document.parameters(), "aoi.parameters");
        return new Aoi(
                AoiId.of(document.id()),
                required(document.name(), "aoi.name"),
                document.countryCode(),
                document.region(),
                point(document.center(), "aoi.center"),
                required(document.radiusKm(), "aoi.radius_km"),
                bbox(document.bbox(), "aoi.bbox"),
                document.timezone(),
                new Destination(
                        required(document.destination(), "aoi.destination").name(),
                        point(document.destination().location(), "aoi.destination.location"),
                        document.destination().osmId()),
                document.riverGauge() == null
                        ? null
                        : new RiverGauge(
                                document.riverGauge().provider(),
                                document.riverGauge().stationCode(),
                                document.riverGauge().stationName()),
                new AoiParameters(
                        orEmpty(parameters.testedWidthsM()),
                        required(parameters.connectedMinWidthM(), "connected_min_width_m"),
                        required(parameters.monitorMinWidthM(), "monitor_min_width_m"),
                        required(parameters.minCoverageFraction(), "min_coverage_fraction"),
                        required(parameters.lowWaterThresholdM(), "low_water_threshold_m"),
                        required(parameters.waterThresholdDb(), "water_threshold_db"),
                        required(parameters.waterHysteresisDb(), "water_hysteresis_db"),
                        required(parameters.fillValueDb(), "fill_value_db"),
                        required(parameters.swathEdgeBufferM(), "swath_edge_buffer_m")));
    }

    private static Pass toPass(PassDocument document, BoundingBox aoiBbox) {
        PassId id = PassId.of(document.id());
        Map<LayerId, LayerImage> layers = new EnumMap<>(LayerId.class);
        if (document.files() != null && document.files().layers() != null) {
            document.files()
                    .layers()
                    .forEach((code, file) -> LayerId.fromCode(code)
                            .ifPresentOrElse(
                                    layerId -> layers.put(layerId, toLayer(layerId, file, aoiBbox)),
                                    () -> log.warn("Ignoring unknown layer '{}' of pass {}", code, id)));
        }
        String statusesPath = document.files() != null && document.files().statuses() != null
                ? document.files().statuses()
                : "passes/" + id + "/statuses.json";
        boolean evaluated = Boolean.TRUE.equals(document.evaluated());
        return new Pass(
                id,
                required(document.acquiredAt(), "acquired_at of " + id),
                document.platform(),
                document.band(),
                document.wavelengthCm(),
                document.product(),
                document.granuleId(),
                document.track(),
                document.frame(),
                document.absoluteOrbit(),
                OrbitDirection.fromCode(document.orbitDirection()).orElse(null),
                document.polarizations(),
                document.pixelSizeM(),
                document.coverageFraction(),
                evaluated,
                document.riverLevelM(),
                document.lowWaterRule() == null
                        ? LowWaterRule.INACTIVE
                        : new LowWaterRule(
                                Boolean.TRUE.equals(document.lowWaterRule().active()),
                                document.lowWaterRule().s1SceneIds(),
                                document.lowWaterRule().unverifiedAreaKm2()),
                toCounts(document.counts(), evaluated),
                document.processedAt(),
                document.methodVersion(),
                statusesPath,
                layers);
    }

    private static StatusCounts toCounts(CountsDocument counts, boolean evaluated) {
        if (counts == null) {
            return StatusCounts.notEvaluated(null);
        }
        if (!evaluated) {
            return StatusCounts.notEvaluated(counts.monitored());
        }
        return new StatusCounts(
                counts.monitored(),
                counts.connected(),
                counts.atRisk(),
                counts.isolated(),
                counts.noData(),
                counts.changed());
    }

    private static LayerImage toLayer(LayerId id, LayerFileDocument file, BoundingBox aoiBbox) {
        return new LayerImage(
                id,
                required(file.path(), "path of layer " + id.code()),
                file.bounds() == null ? aoiBbox : bbox(file.bounds(), "bounds of layer " + id.code()),
                file.widthPx(),
                file.heightPx());
    }

    // --- communities.geojson ------------------------------------------------------------------------------------

    static List<Community> toCommunities(AoiId aoiId, CommunitiesDocument document) {
        List<Community> communities = new ArrayList<>();
        for (FeatureDocument feature : orEmpty(document.features())) {
            try {
                communities.add(toCommunity(feature));
            } catch (RuntimeException e) {
                log.warn("Skipping community '{}' of {}: {}", feature.id(), aoiId, e.getMessage());
            }
        }
        return List.copyOf(communities);
    }

    private static Community toCommunity(FeatureDocument feature) {
        var properties = required(feature.properties(), "properties");
        String id = properties.id() != null ? properties.id() : feature.id();
        var boarding = properties.boardingPoint();
        return new Community(
                CommunityId.of(id),
                required(properties.name(), "name"),
                CommunityKind.fromCode(properties.kind()),
                properties.municipality(),
                properties.osmRef(),
                properties.population(),
                Boolean.TRUE.equals(properties.monitored()),
                ExclusionReason.fromCode(properties.exclusionReason()).orElse(null),
                point(required(feature.geometry(), "geometry").coordinates(), "geometry"),
                boarding == null
                        ? null
                        : new BoardingPoint(
                                point(boarding.location(), "boarding_point.location"),
                                BoardingPointSource.fromCode(boarding.source()).orElse(null),
                                boarding.osmRef(),
                                boarding.distanceFromCenterM()),
                properties.referenceMaxWidthClassM());
    }

    // --- statuses.json ------------------------------------------------------------------------------------------

    static PassStatuses toPassStatuses(AoiId aoiId, Pass pass, StatusesDocument document) {
        if (document.passId() != null && !pass.id().value().equals(document.passId())) {
            throw new IllegalArgumentException(
                    "statuses.json is for pass '" + document.passId() + "', not '" + pass.id() + "'");
        }
        List<CommunityAssessment> assessments = new ArrayList<>();
        for (CommunityStatusDocument entry : orEmpty(document.communities())) {
            try {
                assessments.add(toAssessment(entry));
            } catch (RuntimeException e) {
                log.warn(
                        "Skipping status of '{}' on pass {} of {}: {}",
                        entry.communityId(),
                        pass.id(),
                        aoiId,
                        e.getMessage());
            }
        }
        // A file that doesn't say it was evaluated claims nothing
        boolean evaluated = Boolean.TRUE.equals(document.evaluated());
        return new PassStatuses(pass.id(), document.acquiredAt(), evaluated, assessments);
    }

    private static CommunityAssessment toAssessment(CommunityStatusDocument entry) {
        CommunityId communityId = CommunityId.of(entry.communityId());
        Optional<Status> status = Status.fromCode(entry.status());
        Optional<Reason> reason = Reason.fromCode(entry.reason());
        if (status.isEmpty()) {
            log.warn("Unknown status '{}' of {}: shown as no data", entry.status(), communityId);
        }
        // A missing route_verified never vouches for a connected route
        boolean routeVerified = entry.routeVerified() != null
                ? entry.routeVerified()
                : status.orElse(Status.NO_DATA) != Status.CONNECTED;
        return new CommunityAssessment(
                communityId,
                status.orElse(Status.NO_DATA),
                status.isPresent() ? reason.orElse(null) : null,
                status.isPresent() ? entry.minWidthClassM() : null,
                routeVerified,
                Status.fromCode(entry.previousStatus()).orElse(null),
                entry.previousPassId() == null ? null : PassId.of(entry.previousPassId()),
                Boolean.TRUE.equals(entry.changed()),
                entry.bottleneck() == null || entry.bottleneck().location() == null
                        ? null
                        : new Bottleneck(
                                point(entry.bottleneck().location(), "bottleneck"),
                                entry.bottleneck().widthClassM()),
                toRoute(entry.route()));
    }

    private static Route toRoute(LineStringDocument route) {
        if (route == null || route.coordinates() == null || route.coordinates().size() < 2) {
            return null;
        }
        return new Route(route.coordinates().stream()
                .map(coordinates -> point(coordinates, "route"))
                .toList());
    }

    // --- river_level.json ---------------------------------------------------------------------------------------

    static RiverLevelSeries toRiverLevel(RiverLevelDocument document) {
        List<RiverLevelReading> readings = orEmpty(document.series()).stream()
                .filter(point -> point.date() != null && point.levelM() != null)
                .map(point -> new RiverLevelReading(point.date(), point.levelM()))
                .toList();
        return new RiverLevelSeries(
                document.provider(), document.stationCode(), document.stationName(), document.unit(), readings);
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private static GeoPoint point(List<Double> coordinates, String what) {
        if (coordinates == null || coordinates.size() != 2 || coordinates.contains(null)) {
            throw new IllegalArgumentException(what + " must be [lon, lat]");
        }
        return new GeoPoint(coordinates.get(0), coordinates.get(1));
    }

    private static BoundingBox bbox(List<Double> values, String what) {
        if (values == null || values.size() != 4 || values.contains(null)) {
            throw new IllegalArgumentException(what + " must be [west, south, east, north]");
        }
        return new BoundingBox(values.get(0), values.get(1), values.get(2), values.get(3));
    }

    private static <T> T required(T value, String what) {
        return Objects.requireNonNull(value, what + " is missing");
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
