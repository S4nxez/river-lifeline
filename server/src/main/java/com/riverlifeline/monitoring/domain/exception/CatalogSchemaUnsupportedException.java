package com.riverlifeline.monitoring.domain.exception;

import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.shared.domain.SchemaVersion;

/** The catalog was written for another major version of the contract. */
public class CatalogSchemaUnsupportedException extends MonitoringException {
    private final transient SchemaVersion found;

    public CatalogSchemaUnsupportedException(AoiId aoiId, SchemaVersion found, int supportedMajor) {
        super("The catalog of AOI '" + aoiId + "' has schema_version " + found + "; this backend reads major "
                + supportedMajor + " only.");
        this.found = found;
    }

    public SchemaVersion found() {
        return found;
    }
}
