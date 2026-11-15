package com.riverlifeline.monitoring.application.port.out;

import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.earlywarning.EarlyWarningReport;
import java.util.Optional;

/** The experimental early warning of an AOI; empty when none was published. */
public interface EarlyWarningRepository {
    /**
     * @throws com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException if the published file is
     *     malformed
     */
    Optional<EarlyWarningReport> find(AoiId aoiId);
}
