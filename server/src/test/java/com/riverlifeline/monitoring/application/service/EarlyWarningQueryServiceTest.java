package com.riverlifeline.monitoring.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riverlifeline.monitoring.domain.exception.EarlyWarningNotFoundException;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.earlywarning.EarlyWarningReport;
import com.riverlifeline.shared.domain.SchemaVersion;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EarlyWarningQueryServiceTest {
    static final EarlyWarningReport REPORT =
            new EarlyWarningReport(AoiId.of("tefe"), new SchemaVersion(1, 0, 0), "{\"aoi_id\":\"tefe\"}");

    @Test
    void servesThePublishedEarlyWarning() {
        var service = new EarlyWarningQueryService(
                aoi -> aoi.value().equals("tefe") ? Optional.of(REPORT) : Optional.empty());

        assertThat(service.earlyWarning("tefe")).isEqualTo(REPORT);
    }

    @Test
    void anAoiWithoutOneOrAnInvalidIdHasNone() {
        var service = new EarlyWarningQueryService(aoi -> Optional.empty());

        assertThatThrownBy(() -> service.earlyWarning("tefe")).isInstanceOf(EarlyWarningNotFoundException.class);
        assertThatThrownBy(() -> service.earlyWarning("../etc")).isInstanceOf(EarlyWarningNotFoundException.class);
    }

    @Test
    void aReportNeedsItsSchemaMajorAndAContent() {
        assertThatThrownBy(() -> new EarlyWarningReport(AoiId.of("tefe"), new SchemaVersion(2, 0, 0), "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EarlyWarningReport(AoiId.of("tefe"), new SchemaVersion(1, 0, 0), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
