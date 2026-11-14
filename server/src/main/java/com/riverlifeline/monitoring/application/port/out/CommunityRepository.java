package com.riverlifeline.monitoring.application.port.out;

import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.community.Community;
import java.util.List;

/** The communities of an AOI, monitored or not. */
public interface CommunityRepository {

    /** @throws com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException if they can't be read */
    List<Community> findAll(AoiId aoiId);
}
