package com.riverlifeline.monitoring.application.port.out;

import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.status.PassStatuses;

/** The per-community results of a pass. They never change, so implementations may cache them. */
public interface PassStatusRepository {

    /** @throws com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException if they can't be read */
    PassStatuses find(AoiId aoiId, Pass pass);
}
