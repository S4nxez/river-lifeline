package com.riverlifeline.monitoring.domain.exception;

/** The AOI has no early warning published (always the case outside the files mode for now). */
public class EarlyWarningNotFoundException extends MonitoringException {
    public EarlyWarningNotFoundException(String aoiId) {
        super("AOI '" + aoiId + "' has no early warning.");
    }
}
