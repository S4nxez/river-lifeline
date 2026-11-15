package com.riverlifeline.monitoring.domain.model.earlywarning;

import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.shared.domain.SchemaVersion;
import java.util.Objects;

/**
 * The experimental early warning of an AOI ({@code aois/<id>/early_warning.json}, CONTRACTS.md): computed offline by
 * the worker's CLI, served as published. It never changes a status, a count or a colour of the main record.
 *
 * @param json the document, already checked against the contract's required fields
 */
public record EarlyWarningReport(AoiId aoiId, SchemaVersion schemaVersion, String json) {
    public static final int SUPPORTED_SCHEMA_MAJOR = 1;

    public EarlyWarningReport {
        Objects.requireNonNull(aoiId, "aoiId");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        if (schemaVersion.major() != SUPPORTED_SCHEMA_MAJOR) {
            throw new IllegalArgumentException(
                    "early warning schema " + schemaVersion + " is not version " + SUPPORTED_SCHEMA_MAJOR);
        }
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("the early warning document is empty");
        }
    }
}
