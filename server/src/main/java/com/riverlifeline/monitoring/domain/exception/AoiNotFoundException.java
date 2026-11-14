package com.riverlifeline.monitoring.domain.exception;

public class AoiNotFoundException extends MonitoringException {
    public AoiNotFoundException(String aoiId) {
        super("AOI '" + aoiId + "' does not exist.");
    }
}
