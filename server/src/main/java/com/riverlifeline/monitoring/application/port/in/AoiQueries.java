package com.riverlifeline.monitoring.application.port.in;

import com.riverlifeline.monitoring.application.view.Views.AoiOverview;
import com.riverlifeline.monitoring.application.view.Views.CatalogVersion;
import com.riverlifeline.monitoring.application.view.Views.RiverLevel;
import com.riverlifeline.monitoring.application.view.Views.Summary;
import com.riverlifeline.monitoring.domain.model.aoi.Aoi;
import java.time.LocalDate;
import java.util.List;

/** Endpoints #3 to #6 and #11. AOI ids arrive raw: an invalid one is simply an AOI that doesn't exist. */
public interface AoiQueries {

    List<Aoi> listAois();

    AoiOverview getAoi(String aoiId);

    CatalogVersion catalogVersion(String aoiId);

    /** For the latest evaluated pass acquired on or before {@code date} (UTC), or the latest one if null. */
    Summary summary(String aoiId, LocalDate date);

    /** Readings between both days, inclusive; null bounds are open. */
    RiverLevel riverLevel(String aoiId, LocalDate from, LocalDate to);
}
