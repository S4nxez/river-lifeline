package com.riverlifeline.monitoring.domain.exception;

import com.riverlifeline.monitoring.domain.model.aoi.AoiId;

/** The processing output of an AOI is missing, unreadable or inconsistent. */
public class CatalogUnavailableException extends MonitoringException {
    public CatalogUnavailableException(AoiId aoiId, String reason) {
        super("The data of AOI '" + aoiId + "' is unavailable: " + reason);
    }

    public CatalogUnavailableException(AoiId aoiId, String reason, Throwable cause) {
        super("The data of AOI '" + aoiId + "' is unavailable: " + reason, cause);
    }

    private CatalogUnavailableException(String message) {
        super(message);
    }

    /** Processing hasn't published a catalog for any AOI yet. */
    public static CatalogUnavailableException noAoiPublished() {
        return new CatalogUnavailableException("No AOI has a catalog yet.");
    }
}
