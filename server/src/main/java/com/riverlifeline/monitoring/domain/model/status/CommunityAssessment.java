package com.riverlifeline.monitoring.domain.model.status;

import com.riverlifeline.monitoring.domain.model.community.CommunityId;
import com.riverlifeline.monitoring.domain.model.pass.PassId;
import java.util.Objects;

/**
 * The status of one monitored community on one pass, as the processing pipeline computed it.
 *
 * <p>Guiding principle (CONTRACTS.md 0.2): a conservative error is acceptable, showing a route that doesn't exist is
 * not. When in doubt the backend degrades a status, it never upgrades it.
 */
public record CommunityAssessment(
        CommunityId communityId,
        Status status,
        Reason reason,
        Integer minWidthClassM,
        boolean routeVerified,
        Status previousStatus,
        PassId previousPassId,
        boolean changed,
        Bottleneck bottleneck,
        Route route) {

    public CommunityAssessment {
        Objects.requireNonNull(communityId, "communityId");
        Objects.requireNonNull(status, "status");
    }

    /** A community the pass says nothing about: no status can be claimed for it. */
    public static CommunityAssessment noData(CommunityId communityId, Reason reason) {
        if (reason.status() != Status.NO_DATA) {
            throw new IllegalArgumentException("Not a no-data reason: " + reason);
        }
        return new CommunityAssessment(communityId, Status.NO_DATA, reason, null, true, null, null, false, null, null);
    }

    /**
     * The same assessment, degraded to {@code at_risk} when it claims {@code connected} without the evidence the
     * contract requires for it: reason {@code route_ge_60m}, a verified route and a width class of at least {@code
     * connectedMinWidthM}.
     */
    public CommunityAssessment degradedIfUnsupported(int connectedMinWidthM) {
        if (status != Status.CONNECTED) {
            return this;
        }
        boolean supported = reason == Reason.ROUTE_GE_60M
                && routeVerified
                && minWidthClassM != null
                && minWidthClassM >= connectedMinWidthM;
        if (supported) {
            return this;
        }
        Reason degraded;
        if (!routeVerified) {
            degraded = Reason.ROUTE_UNVERIFIED;
        } else if (minWidthClassM != null) {
            degraded = Reason.NARROW_ROUTE;
        } else {
            degraded = Reason.NO_ROUTE_UNCONFIRMED;
        }
        return new CommunityAssessment(
                communityId,
                Status.AT_RISK,
                degraded,
                minWidthClassM,
                routeVerified,
                previousStatus,
                previousPassId,
                previousStatus != null && previousStatus != Status.AT_RISK,
                bottleneck,
                route);
    }

    /** On a pass that was not evaluated nothing is claimed, whatever the file says (CONTRACTS.md 4.4). */
    public CommunityAssessment asNotEvaluated() {
        return new CommunityAssessment(
                communityId,
                Status.NO_DATA,
                Reason.PASS_NOT_EVALUATED,
                null,
                true,
                previousStatus,
                previousPassId,
                false,
                null,
                null);
    }
}
