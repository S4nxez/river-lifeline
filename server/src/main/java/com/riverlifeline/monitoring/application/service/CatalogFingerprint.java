package com.riverlifeline.monitoring.application.service;

import com.riverlifeline.monitoring.domain.model.catalog.Catalog;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** The {@code etag} the front polls (#5): it changes whenever processing publishes or reprocesses a pass. */
final class CatalogFingerprint {
    private CatalogFingerprint() {}

    static String etagOf(Catalog catalog) {
        StringBuilder content = new StringBuilder()
                .append(catalog.schemaVersion())
                .append('|')
                .append(catalog.generatedAt())
                .append('|')
                .append(catalog.method() == null ? "" : catalog.method().version());
        for (Pass pass : catalog.passes()) {
            content.append('|')
                    .append(pass.id())
                    .append(',')
                    .append(pass.evaluated())
                    .append(',')
                    .append(pass.methodVersion())
                    .append(',')
                    .append(pass.processedAt());
        }
        CRC32 crc = new CRC32();
        crc.update(content.toString().getBytes(StandardCharsets.UTF_8));
        return "\"%08x\"".formatted(crc.getValue());
    }
}
