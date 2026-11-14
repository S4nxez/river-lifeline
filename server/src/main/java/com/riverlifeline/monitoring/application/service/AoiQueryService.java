package com.riverlifeline.monitoring.application.service;

import com.riverlifeline.monitoring.application.exception.InvalidParameterException;
import com.riverlifeline.monitoring.application.port.in.AoiQueries;
import com.riverlifeline.monitoring.application.port.out.CatalogRepository;
import com.riverlifeline.monitoring.application.port.out.RiverLevelRepository;
import com.riverlifeline.monitoring.application.view.Views.AoiOverview;
import com.riverlifeline.monitoring.application.view.Views.CatalogVersion;
import com.riverlifeline.monitoring.application.view.Views.RiverLevel;
import com.riverlifeline.monitoring.application.view.Views.Summary;
import com.riverlifeline.monitoring.domain.model.aoi.Aoi;
import com.riverlifeline.monitoring.domain.model.aoi.RiverGauge;
import com.riverlifeline.monitoring.domain.model.catalog.Catalog;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.riverlevel.RiverLevelSeries;
import com.riverlifeline.monitoring.domain.service.HeadlinePolicy;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class AoiQueryService implements AoiQueries {
    private final Catalogs catalogs;
    private final RiverLevelRepository riverLevels;

    public AoiQueryService(CatalogRepository catalogs, RiverLevelRepository riverLevels) {
        this.catalogs = new Catalogs(catalogs);
        this.riverLevels = Objects.requireNonNull(riverLevels, "riverLevels");
    }

    @Override
    public List<Aoi> listAois() {
        return catalogs.repository().findAoiIds().stream()
                .map(id -> catalogs.repository().load(id).aoi())
                .toList();
    }

    @Override
    public AoiOverview getAoi(String aoiId) {
        Catalog catalog = catalogs.load(aoiId);
        List<Pass> passes = catalog.passes();
        return new AoiOverview(
                catalog.aoi(),
                passes.size(),
                catalog.evaluatedPasses().size(),
                passes.isEmpty() ? null : passes.getFirst().acquiredAt(),
                catalog.latestPass().map(Pass::acquiredAt).orElse(null));
    }

    @Override
    public CatalogVersion catalogVersion(String aoiId) {
        Catalog catalog = catalogs.load(aoiId);
        return new CatalogVersion(
                catalog.aoiId(),
                catalog.schemaVersion().toString(),
                catalog.generatedAt(),
                CatalogFingerprint.etagOf(catalog),
                catalog.latestPass().map(Pass::id).orElse(null),
                catalog.latestEvaluatedPass().map(Pass::id).orElse(null));
    }

    @Override
    public Summary summary(String aoiId, LocalDate date) {
        Catalog catalog = catalogs.load(aoiId);
        Optional<Pass> pass =
                date == null ? catalog.latestEvaluatedPass() : catalog.latestEvaluatedPassOnOrBefore(date);
        LocalDate day = date != null ? date : pass.map(Pass::acquiredOn).orElse(null);
        return new Summary(
                catalog.aoiId(),
                day,
                pass.orElse(null),
                HeadlinePolicy.headlineFor(
                        catalog.aoi().name(), pass.map(Pass::counts).orElse(null)),
                catalog.generatedAt());
    }

    @Override
    public RiverLevel riverLevel(String aoiId, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidParameterException("from", "'from' (" + from + ") is after 'to' (" + to + ").");
        }
        Catalog catalog = catalogs.load(aoiId);
        Double threshold = catalog.aoi().parameters().lowWaterThresholdM();
        Optional<RiverLevelSeries> series = riverLevels.find(catalog.aoiId());
        if (series.isPresent()) {
            RiverLevelSeries levels = series.get();
            return new RiverLevel(
                    levels.provider(),
                    levels.stationCode(),
                    levels.stationName(),
                    levels.unit(),
                    threshold,
                    levels.between(from, to));
        }
        RiverGauge gauge = catalog.aoi().riverGauge();
        return new RiverLevel(
                gauge == null ? null : gauge.provider(),
                gauge == null ? null : gauge.stationCode(),
                gauge == null ? null : gauge.stationName(),
                "m",
                threshold,
                List.of());
    }
}
