package com.riverlifeline.monitoring.infrastructure.adapter.in.web;

import com.riverlifeline.monitoring.application.view.Views.AoiOverview;
import com.riverlifeline.monitoring.application.view.Views.CatalogVersion;
import com.riverlifeline.monitoring.application.view.Views.CommunityDetail;
import com.riverlifeline.monitoring.application.view.Views.Health;
import com.riverlifeline.monitoring.application.view.Views.Meta;
import com.riverlifeline.monitoring.application.view.Views.PassAssessment;
import com.riverlifeline.monitoring.application.view.Views.PassCommunities;
import com.riverlifeline.monitoring.application.view.Views.PassDetail;
import com.riverlifeline.monitoring.application.view.Views.PassEntry;
import com.riverlifeline.monitoring.application.view.Views.RiverLevel;
import com.riverlifeline.monitoring.application.view.Views.Summary;
import com.riverlifeline.monitoring.domain.model.aoi.Aoi;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.aoi.AoiParameters;
import com.riverlifeline.monitoring.domain.model.community.BoardingPoint;
import com.riverlifeline.monitoring.domain.model.community.Community;
import com.riverlifeline.monitoring.domain.model.community.CommunitySnapshot;
import com.riverlifeline.monitoring.domain.model.pass.LayerId;
import com.riverlifeline.monitoring.domain.model.pass.LayerImage;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.pass.PassId;
import com.riverlifeline.monitoring.domain.model.pass.SatelliteMission;
import com.riverlifeline.monitoring.domain.model.pass.StatusCounts;
import com.riverlifeline.monitoring.domain.model.status.Bottleneck;
import com.riverlifeline.monitoring.domain.model.status.CommunityAssessment;
import com.riverlifeline.monitoring.domain.model.status.Reason;
import com.riverlifeline.monitoring.domain.model.status.Route;
import com.riverlifeline.monitoring.domain.model.status.Status;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.AoiListItemResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.AoiResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.BoardingPointResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.BoardingPointSummaryResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.BottleneckResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.CatalogStateResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.CatalogVersionResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.CommunityFeatureResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.CommunityPropertiesResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.CommunityResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.CountsResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.CurrentStatusResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.DestinationResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.HealthResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.HistoryEntryResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.ImageCardResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.LayerInfoResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.LayerResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.LineStringResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.LowWaterRuleResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.MetaResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.ParametersResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.PassCommunitiesResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.PassDetailResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.PassResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.PointResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.ReasonResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.RiverGaugeResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.RiverLevelItemResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.RiverLevelResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.StatusDefinitionResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.SummaryResponse;
import com.riverlifeline.shared.domain.BoundingBox;
import com.riverlifeline.shared.domain.GeoPoint;
import java.util.List;

/** Turns the application's read models into the JSON bodies of contract 2. */
final class ApiMapper {
    private ApiMapper() {}

    static HealthResponse health(Health health) {
        return new HealthResponse(
                health.ok() ? "ok" : "degraded",
                health.apiVersion(),
                new CatalogStateResponse(
                        health.catalog().loaded(),
                        health.catalog().schemaVersion(),
                        health.catalog().generatedAt()));
    }

    static MetaResponse meta(Meta meta) {
        return new MetaResponse(
                meta.aoiId().value(),
                meta.apiVersion(),
                meta.schemaVersion(),
                meta.methodVersion(),
                meta.statusTable().stream()
                        .map(row -> new StatusDefinitionResponse(
                                row.status().code(),
                                row.label(),
                                row.color(),
                                row.shape(),
                                row.order(),
                                row.description()))
                        .toList(),
                meta.reasons().stream()
                        .map(reason -> new ReasonResponse(
                                reason.reason().code(), reason.reason().status().code(), reason.label()))
                        .toList(),
                meta.layers().stream()
                        .map(layer -> new LayerInfoResponse(
                                layer.code(), layer.label(), layer.defaultVisible(), layer.defaultOpacity()))
                        .toList());
    }

    static AoiListItemResponse aoiListItem(Aoi aoi) {
        return new AoiListItemResponse(
                aoi.id().value(), aoi.name(), point(aoi.center()), number(aoi.radiusKm()), bbox(aoi.bbox()));
    }

    static AoiResponse aoi(AoiOverview overview) {
        Aoi aoi = overview.aoi();
        AoiParameters parameters = aoi.parameters();
        return new AoiResponse(
                aoi.id().value(),
                aoi.name(),
                aoi.countryCode(),
                aoi.region(),
                point(aoi.center()),
                number(aoi.radiusKm()),
                bbox(aoi.bbox()),
                aoi.timezone(),
                new DestinationResponse(
                        aoi.destination().name(),
                        point(aoi.destination().location()),
                        aoi.destination().osmId()),
                aoi.riverGauge() == null
                        ? null
                        : new RiverGaugeResponse(
                                aoi.riverGauge().provider(),
                                aoi.riverGauge().stationCode(),
                                aoi.riverGauge().stationName()),
                new ParametersResponse(
                        parameters.testedWidthsM(),
                        parameters.connectedMinWidthM(),
                        parameters.monitorMinWidthM(),
                        parameters.minCoverageFraction(),
                        parameters.lowWaterThresholdM(),
                        parameters.waterThresholdDb(),
                        parameters.waterHysteresisDb(),
                        parameters.fillValueDb(),
                        parameters.swathEdgeBufferM()),
                overview.passCount(),
                overview.evaluatedPassCount(),
                overview.firstPassAt(),
                overview.latestPassAt());
    }

    static CatalogVersionResponse catalogVersion(CatalogVersion version) {
        return new CatalogVersionResponse(
                version.aoiId().value(),
                version.schemaVersion(),
                version.generatedAt(),
                version.etag(),
                id(version.latestPassId()),
                id(version.latestEvaluatedPassId()));
    }

    static SummaryResponse summary(Summary summary) {
        Pass pass = summary.pass();
        return new SummaryResponse(
                summary.aoiId().value(),
                summary.date(),
                pass == null ? null : pass.id().value(),
                pass == null ? null : pass.acquiredAt(),
                summary.headline().text(),
                summary.headline().level().code(),
                pass == null ? null : counts(pass.counts()),
                pass == null ? null : pass.riverLevelM(),
                pass == null ? null : pass.lowWaterRule().active(),
                summary.catalogGeneratedAt());
    }

    static PassResponse pass(PassEntry entry) {
        Pass pass = entry.pass();
        return new PassResponse(
                pass.id().value(),
                pass.acquiredAt(),
                pass.platform(),
                pass.band(),
                pass.track(),
                pass.frame(),
                pass.orbitDirection() == null ? null : pass.orbitDirection().code(),
                pass.polarizations(),
                number(pass.pixelSizeM()),
                pass.coverageFraction(),
                pass.evaluated(),
                pass.riverLevelM(),
                pass.lowWaterRule().active(),
                counts(pass.counts()),
                id(entry.previousPassId()),
                id(entry.nextPassId()),
                pass.layers().keySet().stream().map(LayerId::code).toList());
    }

    static PassDetailResponse passDetail(PassDetail detail) {
        Pass pass = detail.entry().pass();
        SatelliteMission mission = pass.mission();
        return new PassDetailResponse(
                pass(detail.entry()),
                new ImageCardResponse(
                        mission.satellite(),
                        mission.sensor(),
                        pass.band(),
                        number(pass.wavelengthCm()),
                        pass.product(),
                        pass.granuleId(),
                        pass.track(),
                        pass.frame(),
                        pass.absoluteOrbit(),
                        pass.orbitDirection() == null
                                ? null
                                : pass.orbitDirection().code(),
                        pass.acquiredAt(),
                        number(pass.pixelSizeM()),
                        pass.polarizations(),
                        mission.dataSourceUrl()),
                new LowWaterRuleResponse(
                        pass.lowWaterRule().active(),
                        pass.lowWaterRule().s1SceneIds(),
                        pass.lowWaterRule().unverifiedAreaKm2()),
                pass.methodVersion(),
                pass.processedAt(),
                pass.layers().values().stream()
                        .map(layer -> layer(detail.aoiId(), pass.id(), layer))
                        .toList());
    }

    private static LayerResponse layer(AoiId aoiId, PassId passId, LayerImage layer) {
        return new LayerResponse(
                layer.id().code(),
                ApiPaths.layerUrl(aoiId.value(), passId.value(), layer.id().code()),
                bbox(layer.bounds()),
                layer.widthPx(),
                layer.heightPx());
    }

    static PassCommunitiesResponse passCommunities(PassCommunities view) {
        List<CommunityFeatureResponse> features =
                view.communities().stream().map(ApiMapper::feature).toList();
        return new PassCommunitiesResponse(
                "FeatureCollection",
                view.aoiId().value(),
                view.pass().id().value(),
                view.pass().acquiredAt(),
                features.size(),
                features);
    }

    private static CommunityFeatureResponse feature(CommunitySnapshot snapshot) {
        Community community = snapshot.community();
        CommunityAssessment assessment = snapshot.assessment();
        BoardingPoint boarding = community.boardingPoint();
        return new CommunityFeatureResponse(
                "Feature",
                community.id().value(),
                new PointResponse("Point", point(community.measurementPoint())),
                new CommunityPropertiesResponse(
                        community.id().value(),
                        community.name(),
                        community.kind().code(),
                        community.municipality(),
                        assessment.status().code(),
                        code(assessment.reason()),
                        assessment.minWidthClassM(),
                        assessment.routeVerified(),
                        code(assessment.previousStatus()),
                        assessment.changed(),
                        point(community.villageLocation()),
                        boarding == null
                                ? null
                                : new BoardingPointSummaryResponse(
                                        point(boarding.location()),
                                        boarding.source() == null
                                                ? null
                                                : boarding.source().code(),
                                        boarding.distanceFromCenterM()),
                        bottleneck(assessment.bottleneck())));
    }

    static CommunityResponse community(CommunityDetail detail) {
        Community community = detail.community();
        BoardingPoint boarding = community.boardingPoint();
        PassAssessment current = detail.current();
        return new CommunityResponse(
                community.id().value(),
                community.name(),
                community.kind().code(),
                community.municipality(),
                community.osmRef(),
                community.monitored(),
                community.exclusionReason() == null
                        ? null
                        : community.exclusionReason().code(),
                point(community.villageLocation()),
                boarding == null
                        ? null
                        : new BoardingPointResponse(
                                point(boarding.location()),
                                boarding.source() == null
                                        ? null
                                        : boarding.source().code(),
                                boarding.osmRef(),
                                boarding.distanceFromCenterM()),
                community.referenceMaxWidthClassM(),
                current == null ? null : current(current),
                detail.history().stream()
                        .map(entry -> new HistoryEntryResponse(
                                entry.pass().id().value(),
                                entry.pass().acquiredAt(),
                                entry.assessment().status().code(),
                                code(entry.assessment().reason()),
                                entry.assessment().minWidthClassM(),
                                entry.pass().riverLevelM()))
                        .toList());
    }

    private static CurrentStatusResponse current(PassAssessment current) {
        CommunityAssessment assessment = current.assessment();
        return new CurrentStatusResponse(
                current.pass().id().value(),
                current.pass().acquiredAt(),
                assessment.status().code(),
                code(assessment.reason()),
                assessment.minWidthClassM(),
                assessment.routeVerified(),
                bottleneck(assessment.bottleneck()),
                route(assessment.route()));
    }

    static RiverLevelResponse riverLevel(RiverLevel level) {
        List<RiverLevelItemResponse> items = level.readings().stream()
                .map(reading -> new RiverLevelItemResponse(reading.date(), reading.levelM()))
                .toList();
        return new RiverLevelResponse(
                level.provider(),
                level.stationCode(),
                level.stationName(),
                level.unit(),
                level.lowWaterThresholdM(),
                items,
                items.size());
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    static CountsResponse counts(StatusCounts counts) {
        return new CountsResponse(
                counts.monitored(),
                counts.connected(),
                counts.atRisk(),
                counts.isolated(),
                counts.noData(),
                counts.changed());
    }

    private static BottleneckResponse bottleneck(Bottleneck bottleneck) {
        return bottleneck == null
                ? null
                : new BottleneckResponse(point(bottleneck.location()), bottleneck.widthClassM());
    }

    private static LineStringResponse route(Route route) {
        return route == null
                ? null
                : new LineStringResponse(
                        "LineString",
                        route.coordinates().stream().map(ApiMapper::point).toList());
    }

    static List<Double> point(GeoPoint point) {
        return point == null ? null : List.of(point.lon(), point.lat());
    }

    private static List<Double> bbox(BoundingBox bbox) {
        return List.of(bbox.west(), bbox.south(), bbox.east(), bbox.north());
    }

    private static String id(PassId passId) {
        return passId == null ? null : passId.value();
    }

    private static String code(Reason reason) {
        return reason == null ? null : reason.code();
    }

    private static String code(Status status) {
        return status == null ? null : status.code();
    }

    /** Whole numbers are written without a decimal part ({@code 55}, not {@code 55.0}). */
    static Number number(Double value) {
        if (value == null) {
            return null;
        }
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? (Number) value.longValue() : value;
    }
}
