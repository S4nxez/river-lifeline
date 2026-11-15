package com.riverlifeline.monitoring.application.port.in;

import com.riverlifeline.monitoring.domain.model.earlywarning.EarlyWarningReport;

/** The experimental early warning of an AOI (GET only, as published). */
public interface EarlyWarningQueries {
    /**
     * @throws com.riverlifeline.monitoring.domain.exception.EarlyWarningNotFoundException when the AOI has none
     */
    EarlyWarningReport earlyWarning(String aoiId);
}
