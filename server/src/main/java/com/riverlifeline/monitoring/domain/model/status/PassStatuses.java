package com.riverlifeline.monitoring.domain.model.status;

import com.riverlifeline.monitoring.domain.model.community.CommunityId;
import com.riverlifeline.monitoring.domain.model.pass.PassId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Every monitored community's assessment on one pass ({@code statuses.json}). Immutable, like the pass. */
public final class PassStatuses {
    private final PassId passId;
    private final Instant acquiredAt;
    private final boolean evaluated;
    private final Map<CommunityId, CommunityAssessment> byCommunity;

    public PassStatuses(PassId passId, Instant acquiredAt, boolean evaluated, List<CommunityAssessment> assessments) {
        this.passId = Objects.requireNonNull(passId, "passId");
        this.acquiredAt = acquiredAt;
        this.evaluated = evaluated;
        Map<CommunityId, CommunityAssessment> map = new LinkedHashMap<>();
        for (CommunityAssessment assessment : assessments) {
            if (map.putIfAbsent(assessment.communityId(), assessment) != null) {
                throw new IllegalArgumentException(
                        "Community " + assessment.communityId() + " is listed twice on pass " + passId);
            }
        }
        this.byCommunity = Map.copyOf(map);
    }

    public PassId passId() {
        return passId;
    }

    public Instant acquiredAt() {
        return acquiredAt;
    }

    public boolean evaluated() {
        return evaluated;
    }

    public Optional<CommunityAssessment> find(CommunityId communityId) {
        return Optional.ofNullable(byCommunity.get(communityId));
    }

    public int size() {
        return byCommunity.size();
    }
}
