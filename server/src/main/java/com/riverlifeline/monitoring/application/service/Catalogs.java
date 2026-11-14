package com.riverlifeline.monitoring.application.service;

import com.riverlifeline.monitoring.application.port.out.CatalogRepository;
import com.riverlifeline.monitoring.domain.exception.AoiNotFoundException;
import com.riverlifeline.monitoring.domain.model.Identifier;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.catalog.Catalog;
import java.util.Objects;

/** Loads a catalog from an AOI id as the client sent it. */
final class Catalogs {
    private final CatalogRepository repository;

    Catalogs(CatalogRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    Catalog load(String aoiId) {
        if (!Identifier.isValid(aoiId)) {
            throw new AoiNotFoundException(aoiId);
        }
        return repository.load(AoiId.of(aoiId));
    }

    CatalogRepository repository() {
        return repository;
    }
}
