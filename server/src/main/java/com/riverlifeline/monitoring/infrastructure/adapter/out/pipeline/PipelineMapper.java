package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.monitoring.domain.model.aoi.Aoi;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.aoi.AoiParameters;
import com.riverlifeline.monitoring.domain.model.aoi.Destination;
import com.riverlifeline.monitoring.domain.model.aoi.RiverGauge;
import com.riverlifeline.monitoring.domain.model.catalog.Catalog;
import com.riverlifeline.monitoring.domain.model.catalog.MethodInfo;
import com.riverlifeline.monitoring.domain.model.community.BoardingPoint;
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
import com.riverlifeline.monitoring.domain.model.status.CommunityAssessment;
import com.riverlifeline.monitoring.domain.model.status.PassStatuses;
import com.riverlifeline.monitoring.domain.model.status.Reason;
import com.riverlifeline.monitoring.domain.model.status.Status;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.AssessmentRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.GaugeRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.PassRow;
import com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline.PipelineRows.RasterBox;
import com.riverlifeline.shared.domain.BoundingBox;
import com.riverlifeline.shared.domain.SchemaVersion;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the worker's stored results into the public API's model.
 *
 * <ul>
 *   <li>A pass's statuses apply the whole-record rules: an isolation the next pass has not confirmed yet is at risk
 *       ({@code no_route_unconfirmed}), and each status says what it was on the community's previous evaluated pass.
 *   <li>A community is monitored when its whole-record class is {@code monitored}; inland places are terra firme and
 *       the ones only linked through channels the radar can't resolve are never navigable. Before the first
 *       classification every community counts as monitored.
 *   <li>The layer images are rendered from the stored rasters ({@link RasterLayerImageRepository}): their path names
 *       the raster and the layer, their bounds are the raster's outer edges.
 * </ul>
 */
public final class PipelineMapper {
    /** The public schema the catalog is served with (the same major the file catalogs use). */
    static final SchemaVersion SCHEMA = new SchemaVersion(1, 0, 0);

    static final String PLATFORM = "NISAR";

    private PipelineMapper() {}

    /** Everything the public API serves of an area. */
    public record Snapshot(
            Catalog catalog,
            List<Community> communities,
            Map<PassId, PassStatuses> statuses,
            Optional<RiverLevelSeries> riverLevel) {}

    public static Snapshot snapshot(
            AreaFile area,
            List<PassRow> passes,
            List<AssessmentRow> assessments,
            Map<String, String> classes,
            List<GaugeRow> gauge,
            ObjectMapper json) {
        Set<String> monitored = monitoredIds(area, classes);
        Map<String, List<AssessmentRow>> byPass = new HashMap<>();
        assessments.forEach(row ->
                byPass.computeIfAbsent(row.passId(), k -> new ArrayList<>()).add(row));

        Map<PassId, PassStatuses> statuses = new LinkedHashMap<>();
        Map<String, CommunityAssessment> previous = new HashMap<>();
        Map<String, PassId> previousPass = new HashMap<>();
        List<Pass> catalogPasses = new ArrayList<>();
        for (PassRow row : passes) {
            PassId id = publicPassId(row.passId());
            Instant acquiredAt = acquiredAt(row);
            List<CommunityAssessment> judged = new ArrayList<>();
            int changed = 0;
            if (row.evaluated()) {
                Set<String> unconfirmed = row.counts() == null
                        ? Set.of()
                        : new HashSet<>(row.counts().unconfirmed());
                for (AssessmentRow assessment : byPass.getOrDefault(row.passId(), List.of())) {
                    String cid = assessment.communityId();
                    CommunityAssessment before = previous.get(cid);
                    CommunityAssessment now = assessment(
                            assessment,
                            monitored.contains(cid) && unconfirmed.contains(cid),
                            before,
                            previousPass.get(cid));
                    judged.add(now);
                    if (now.changed() && monitored.contains(cid)) {
                        changed++;
                    }
                    previous.put(cid, now);
                    previousPass.put(cid, id);
                }
            }
            statuses.put(id, new PassStatuses(id, acquiredAt, row.evaluated(), judged));
            catalogPasses.add(pass(row, id, acquiredAt, counts(row, monitored, judged, changed), json));
        }

        Instant generatedAt = passes.stream()
                .map(PassRow::assessedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
        Catalog catalog = new Catalog(
                SCHEMA,
                generatedAt,
                aoi(area),
                List.of(),
                new MethodInfo("pipeline", "NISAR L2 GCOV judged by the worker (api/worker-api.md)"),
                catalogPasses);
        return new Snapshot(catalog, communities(area, classes), statuses, riverLevel(area, gauge));
    }

    /**
     * The public id of a pipeline pass: the API's ids are lower case letters, digits and hyphens, the pipeline's look
     * like {@code 2026-10-23_t161A}, so it becomes {@code 2026-10-23-t161a}.
     */
    static PassId publicPassId(String pipelineId) {
        String id = pipelineId
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return PassId.of(id);
    }

    // --- area -------------------------------------------------------------------------------------------------------

    static Aoi aoi(AreaFile area) {
        AreaFile.Method m = area.method();
        int monitorMin = m.widthsM().isEmpty() ? m.safeWidthM() : m.widthsM().getFirst();
        return new Aoi(
                AoiId.of(area.id()),
                area.name(),
                null,
                area.country(),
                area.center(),
                area.radiusKm(),
                area.bbox(),
                null,
                new Destination(area.hub().name(), area.hub().waterAccess(), null),
                area.gauge() == null
                        ? null
                        : new RiverGauge(
                                "ANA", area.gauge().stationCode(), area.gauge().name()),
                new AoiParameters(
                        m.widthsM(),
                        m.safeWidthM(),
                        monitorMin,
                        m.minCoverage(),
                        m.lowWaterGaugeMaxCm() == null ? 0.0 : m.lowWaterGaugeMaxCm() / 100.0,
                        m.waterDb(),
                        m.waterDbWeak() == null ? m.waterDb() : m.waterDbWeak(),
                        m.validFloorDb(),
                        (int) Math.round(m.edgeMarginM())));
    }

    /** Every community of the area file, with its whole-record class. */
    static List<Community> communities(AreaFile area, Map<String, String> classes) {
        Set<String> monitored = monitoredIds(area, classes);
        return area.communities().stream()
                .map(place -> new Community(
                        CommunityId.of(place.id()),
                        place.name(),
                        kind(place.place()),
                        null,
                        place.id(),
                        null,
                        monitored.contains(place.id()),
                        exclusion(classes.get(place.id())),
                        place.location(),
                        place.access() == null ? null : new BoardingPoint(place.access(), null, null, null),
                        null))
                .toList();
    }

    private static Set<String> monitoredIds(AreaFile area, Map<String, String> classes) {
        Set<String> ids = new HashSet<>();
        for (AreaFile.CommunityPlace place : area.communities()) {
            if (classes.isEmpty() || "monitored".equals(classes.get(place.id()))) {
                ids.add(place.id());
            }
        }
        return ids;
    }

    static CommunityKind kind(String place) {
        return switch (place) {
            case "city", "town" -> CommunityKind.TOWN;
            case "village" -> CommunityKind.VILLAGE;
            case "hamlet" -> CommunityKind.HAMLET;
            default -> CommunityKind.OTHER;
        };
    }

    static ExclusionReason exclusion(String communityClass) {
        if (communityClass == null) {
            return null;
        }
        return switch (communityClass) {
            case "inland" -> ExclusionReason.TERRA_FIRME;
            case "unresolved" -> ExclusionReason.NEVER_NAVIGABLE;
            default -> null;
        };
    }

    // --- passes -----------------------------------------------------------------------------------------------------

    static Instant acquiredAt(PassRow row) {
        return row.date().atTime(LocalTime.parse(row.timeUtc())).toInstant(ZoneOffset.UTC);
    }

    private static Pass pass(PassRow row, PassId id, Instant acquiredAt, StatusCounts counts, ObjectMapper json) {
        return new Pass(
                id,
                acquiredAt,
                PLATFORM,
                "L",
                24.0,
                "GCOV",
                firstGranule(row.granulesJson(), json),
                row.track(),
                null,
                null,
                OrbitDirection.fromCode(row.direction()).orElse(null),
                row.vegetatedKm2() == null ? List.of("HH") : List.of("HH", "HV"),
                row.spacingM(),
                row.coverage(),
                row.evaluated(),
                row.riverLevelCm() == null ? null : row.riverLevelCm() / 100.0,
                new LowWaterRule(
                        Boolean.TRUE.equals(row.lowWater()),
                        row.sentinel1Dates().stream().map(Object::toString).toList(),
                        null),
                counts,
                row.assessedAt(),
                row.methodDigest() == null
                        ? null
                        : row.methodDigest()
                                .substring(0, Math.min(12, row.methodDigest().length())),
                null,
                layers(row));
    }

    /** The layers a pass can show, each rendered from one of its rasters. */
    static Map<LayerId, LayerImage> layers(PassRow row) {
        Map<LayerId, LayerImage> layers = new EnumMap<>(LayerId.class);
        if (row.radar() != null) {
            layers.put(LayerId.RADAR_HH, layer(LayerId.RADAR_HH, row.radar()));
        }
        if (row.sentinel1Vv() != null) {
            layers.put(LayerId.SENTINEL1_VV, layer(LayerId.SENTINEL1_VV, row.sentinel1Vv()));
        }
        if (row.water() != null) {
            layers.put(LayerId.WATER, layer(LayerId.WATER, row.water()));
            layers.put(LayerId.NODATA, layer(LayerId.NODATA, row.water()));
            if (row.vegetatedKm2() != null) {
                layers.put(LayerId.FLOODED_VEGETATION, layer(LayerId.FLOODED_VEGETATION, row.water()));
            }
        }
        if (row.change() != null && row.change().raster() != null) {
            layers.put(LayerId.CHANGE, layer(LayerId.CHANGE, row.change().raster()));
        }
        return layers;
    }

    private static LayerImage layer(LayerId id, RasterBox raster) {
        return new LayerImage(
                id,
                LayerPath.of(raster.id(), id),
                new BoundingBox(raster.west(), raster.south(), raster.east(), raster.north()),
                raster.width(),
                raster.height());
    }

    /** Counts over monitored communities: as the classification stored them, else over the pass's own statuses. */
    private static StatusCounts counts(
            PassRow row, Set<String> monitored, List<CommunityAssessment> judged, int changed) {
        if (!row.evaluated()) {
            return StatusCounts.notEvaluated(monitored.size());
        }
        if (row.counts() != null) {
            var c = row.counts();
            return new StatusCounts(monitored.size(), c.connected(), c.atRisk(), c.isolated(), c.noData(), changed);
        }
        Map<Status, Integer> tally = new EnumMap<>(Status.class);
        for (Status status : Status.values()) {
            tally.put(status, 0);
        }
        Set<String> seen = new HashSet<>();
        for (CommunityAssessment a : judged) {
            if (monitored.contains(a.communityId().value())) {
                tally.merge(a.status(), 1, Integer::sum);
                seen.add(a.communityId().value());
            }
        }
        tally.merge(Status.NO_DATA, monitored.size() - seen.size(), Integer::sum);
        return new StatusCounts(
                monitored.size(),
                tally.get(Status.CONNECTED),
                tally.get(Status.AT_RISK),
                tally.get(Status.ISOLATED),
                tally.get(Status.NO_DATA),
                changed);
    }

    private static String firstGranule(String granulesJson, ObjectMapper json) {
        if (granulesJson == null) {
            return null;
        }
        try {
            JsonNode granules = json.readTree(granulesJson);
            return granules.isArray() && !granules.isEmpty()
                    ? granules.get(0).path("name").asText(null)
                    : null;
        } catch (Exception e) {
            return null;
        }
    }

    // --- statuses ---------------------------------------------------------------------------------------------------

    /**
     * A community's status on a pass, with the reason the public API shows. {@code unconfirmed} marks a monitored
     * community isolated on this pass but not on its previous one with data: it is at risk until a second pass
     * confirms the isolation.
     */
    static CommunityAssessment assessment(
            AssessmentRow row, boolean unconfirmed, CommunityAssessment previous, PassId previousPassId) {
        Status status;
        Reason reason;
        switch (row.status()) {
            case "connected" -> {
                status = Status.CONNECTED;
                reason = Reason.ROUTE_GE_60M;
            }
            case "at_risk" -> {
                status = Status.AT_RISK;
                reason = row.unverifiedRoute() ? Reason.ROUTE_UNVERIFIED : Reason.NARROW_ROUTE;
            }
            case "isolated" -> {
                status = unconfirmed ? Status.AT_RISK : Status.ISOLATED;
                reason = unconfirmed ? Reason.NO_ROUTE_UNCONFIRMED : Reason.NO_ROUTE_CONFIRMED;
            }
            default -> {
                status = Status.NO_DATA;
                // a distance to water means the place was covered: only a gap on its route left it undecided
                reason = row.distanceToWaterM() != null ? Reason.ROUTE_THROUGH_NODATA : Reason.OUTSIDE_COVERAGE;
            }
        }
        Status before = previous == null ? null : previous.status();
        return new CommunityAssessment(
                CommunityId.of(row.communityId()),
                status,
                reason,
                status == Status.NO_DATA ? null : row.bottleneckM(),
                !row.unverifiedRoute(),
                before,
                previous == null ? null : previousPassId,
                before != null && before != status,
                null,
                null);
    }

    // --- river level ------------------------------------------------------------------------------------------------

    static Optional<RiverLevelSeries> riverLevel(AreaFile area, List<GaugeRow> gauge) {
        if (area.gauge() == null || gauge.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RiverLevelSeries(
                "ANA",
                area.gauge().stationCode(),
                area.gauge().name(),
                "m",
                gauge.stream()
                        .map(day -> new RiverLevelReading(day.day(), day.levelCm() / 100.0))
                        .toList()));
    }
}
