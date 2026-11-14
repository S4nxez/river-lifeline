package com.riverlifeline.monitoring.application.port.out;

import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.catalog.Catalog;
import java.util.List;

/** Where the processing pipeline publishes each AOI's catalog. */
public interface CatalogRepository {

    /** Every AOI that has a catalog, sorted by id. */
    List<AoiId> findAoiIds();

    /**
     * The current catalog of an AOI.
     *
     * @throws com.riverlifeline.monitoring.domain.exception.AoiNotFoundException if the AOI doesn't exist
     * @throws com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException if it can't be read
     * @throws com.riverlifeline.monitoring.domain.exception.CatalogSchemaUnsupportedException if its major differs
     */
    Catalog load(AoiId aoiId);
}
