package com.riverlifeline.monitoring.domain.model.community;

import com.riverlifeline.monitoring.domain.model.status.CommunityAssessment;
import java.util.Objects;

/** A community together with its assessment on one pass: one marker on the map, one row in the list. */
public record CommunitySnapshot(Community community, CommunityAssessment assessment) {
    public CommunitySnapshot {
        Objects.requireNonNull(community, "community");
        Objects.requireNonNull(assessment, "assessment");
    }
}
