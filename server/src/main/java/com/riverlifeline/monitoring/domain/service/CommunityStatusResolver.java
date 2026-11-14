package com.riverlifeline.monitoring.domain.service;

import com.riverlifeline.monitoring.domain.model.community.CommunityId;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.status.CommunityAssessment;
import com.riverlifeline.monitoring.domain.model.status.PassStatuses;
import com.riverlifeline.monitoring.domain.model.status.Reason;
import java.util.Optional;

/**
 * The status a monitored community is shown with on a pass. It only reads what processing published, and only ever
 * degrades it: a pass that was not evaluated claims nothing, a community the pass doesn't list has no data, and a
 * {@code connected} without the evidence for it drops to {@code at_risk}.
 */
public final class CommunityStatusResolver {
    private CommunityStatusResolver() {}

    public static CommunityAssessment resolve(
            Pass pass, PassStatuses statuses, CommunityId communityId, int connectedMinWidthM) {
        Optional<CommunityAssessment> published = statuses.find(communityId);
        if (!pass.evaluated() || !statuses.evaluated()) {
            return published
                    .map(CommunityAssessment::asNotEvaluated)
                    .orElseGet(() -> CommunityAssessment.noData(communityId, Reason.PASS_NOT_EVALUATED));
        }
        return published
                .map(assessment -> assessment.degradedIfUnsupported(connectedMinWidthM))
                .orElseGet(() -> CommunityAssessment.noData(communityId, Reason.OUTSIDE_COVERAGE));
    }
}
