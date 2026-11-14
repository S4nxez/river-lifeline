package com.riverlifeline.monitoring.domain.exception;

import com.riverlifeline.monitoring.domain.model.aoi.AoiId;

public class CommunityNotFoundException extends MonitoringException {
    public CommunityNotFoundException(AoiId aoiId, String communityId) {
        super("Community '" + communityId + "' does not exist in AOI '" + aoiId + "'.");
    }
}
