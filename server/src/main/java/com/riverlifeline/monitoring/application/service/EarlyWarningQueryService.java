package com.riverlifeline.monitoring.application.service;

import com.riverlifeline.monitoring.application.port.in.EarlyWarningQueries;
import com.riverlifeline.monitoring.application.port.out.EarlyWarningRepository;
import com.riverlifeline.monitoring.domain.exception.EarlyWarningNotFoundException;
import com.riverlifeline.monitoring.domain.model.Identifier;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.earlywarning.EarlyWarningReport;
import java.util.Objects;

/** Serves the published early warning; computes nothing. */
public class EarlyWarningQueryService implements EarlyWarningQueries {
    private final EarlyWarningRepository repository;

    public EarlyWarningQueryService(EarlyWarningRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public EarlyWarningReport earlyWarning(String aoiId) {
        if (!Identifier.isValid(aoiId)) {
            throw new EarlyWarningNotFoundException(aoiId);
        }
        return repository.find(AoiId.of(aoiId)).orElseThrow(() -> new EarlyWarningNotFoundException(aoiId));
    }
}
