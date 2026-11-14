package com.riverlifeline.monitoring.domain.exception;

import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.pass.PassId;

public class LayerNotFoundException extends MonitoringException {
    public LayerNotFoundException(AoiId aoiId, PassId passId, String layer) {
        super("Pass '" + passId + "' of AOI '" + aoiId + "' has no layer '" + layer + "'.");
    }
}
