package com.riverlifeline.monitoring.application.service;

import com.riverlifeline.monitoring.application.port.in.SystemQueries;
import com.riverlifeline.monitoring.application.port.out.CatalogRepository;
import com.riverlifeline.monitoring.application.view.Views.CatalogHealth;
import com.riverlifeline.monitoring.application.view.Views.Health;
import com.riverlifeline.monitoring.application.view.Views.Meta;
import com.riverlifeline.monitoring.application.view.Views.ReasonLabel;
import com.riverlifeline.monitoring.domain.exception.CatalogSchemaUnsupportedException;
import com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.catalog.Catalog;
import com.riverlifeline.monitoring.domain.model.pass.LayerId;
import com.riverlifeline.monitoring.domain.model.status.Reason;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Health and meta describe one catalog: the configured default AOI, or else the first AOI processing published
 * (alphabetical). No area is assumed.
 */
public class SystemQueryService implements SystemQueries {
    private final CatalogRepository catalogs;
    private final Optional<AoiId> configuredDefault;

    public SystemQueryService(CatalogRepository catalogs, Optional<AoiId> configuredDefault) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.configuredDefault = Objects.requireNonNull(configuredDefault, "configuredDefault");
    }

    @Override
    public Health health() {
        try {
            Catalog catalog = defaultCatalog();
            return new Health(
                    true,
                    ApiVersion.CURRENT,
                    new CatalogHealth(true, catalog.schemaVersion().toString(), catalog.generatedAt()));
        } catch (CatalogSchemaUnsupportedException e) {
            return new Health(
                    false,
                    ApiVersion.CURRENT,
                    new CatalogHealth(false, e.found().toString(), null));
        } catch (RuntimeException e) {
            return new Health(false, ApiVersion.CURRENT, new CatalogHealth(false, null, null));
        }
    }

    @Override
    public Meta meta() {
        Catalog catalog = defaultCatalog();
        List<ReasonLabel> reasons = Arrays.stream(Reason.values())
                .map(reason ->
                        new ReasonLabel(reason, reason.labelFor(catalog.aoi().parameters())))
                .toList();
        return new Meta(
                catalog.aoiId(),
                ApiVersion.CURRENT,
                catalog.schemaVersion().toString(),
                catalog.method() == null ? null : catalog.method().version(),
                catalog.statusTable(),
                reasons,
                List.of(LayerId.values()));
    }

    private Catalog defaultCatalog() {
        AoiId aoiId = configuredDefault
                .or(() -> catalogs.findAoiIds().stream().findFirst())
                .orElseThrow(CatalogUnavailableException::noAoiPublished);
        return catalogs.load(aoiId);
    }
}
