package com.riverlifeline.monitoring.application.port.out;

import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.riverlevel.RiverLevelSeries;
import java.util.Optional;

/** The river gauge time series of an AOI; empty when none was published. */
public interface RiverLevelRepository {
    Optional<RiverLevelSeries> find(AoiId aoiId);
}
