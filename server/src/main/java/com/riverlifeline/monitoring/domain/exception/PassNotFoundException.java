package com.riverlifeline.monitoring.domain.exception;

import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.pass.PassSelector;

public class PassNotFoundException extends MonitoringException {
    public PassNotFoundException(AoiId aoiId, PassSelector selector) {
        super(
                selector.isLatest()
                        ? "AOI '" + aoiId + "' has no evaluated pass yet."
                        : "Pass '" + selector + "' does not exist in AOI '" + aoiId + "'.");
    }
}
