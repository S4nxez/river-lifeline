package com.riverlifeline.monitoring.domain.model.status;

import com.riverlifeline.monitoring.domain.model.aoi.AoiParameters;
import java.util.Arrays;
import java.util.Optional;

/**
 * Why a community got its status on a pass (CONTRACTS.md 1.1). Each reason implies exactly one status. The codes are
 * fixed by the contract; the labels take the thresholds from each AOI's parameters, never from code.
 */
public enum Reason {
    ROUTE_GE_60M("route_ge_60m", Status.CONNECTED, "Route ≥ %1$d m"),
    ROUTE_UNVERIFIED("route_unverified", Status.AT_RISK, "Route not verified at low water"),
    NARROW_ROUTE("narrow_route", Status.AT_RISK, "Only channels narrower than %1$d m"),
    NO_ROUTE_UNCONFIRMED("no_route_unconfirmed", Status.AT_RISK, "Route lost (awaiting confirmation)"),
    NO_ROUTE_CONFIRMED("no_route_confirmed", Status.ISOLATED, "No water route (confirmed)"),
    OUTSIDE_COVERAGE("outside_coverage", Status.NO_DATA, "Not covered by this pass"),
    ROUTE_THROUGH_NODATA("route_through_nodata", Status.NO_DATA, "Route crosses an image gap"),
    PASS_NOT_EVALUATED("pass_not_evaluated", Status.NO_DATA, "Pass covers < %2$d %% of the area");

    private final String code;
    private final Status status;
    private final String labelTemplate;

    Reason(String code, Status status, String labelTemplate) {
        this.code = code;
        this.status = status;
        this.labelTemplate = labelTemplate;
    }

    public String code() {
        return code;
    }

    public Status status() {
        return status;
    }

    /** The label shown for this reason in an AOI, e.g. "Route ≥ 60 m" when its safe width is 60 m. */
    public String labelFor(AoiParameters parameters) {
        long coveragePercent = Math.round(parameters.minCoverageFraction() * 100);
        return labelTemplate.formatted(parameters.connectedMinWidthM(), coveragePercent);
    }

    public static Optional<Reason> fromCode(String code) {
        return Arrays.stream(values())
                .filter(reason -> reason.code.equals(code))
                .findFirst();
    }
}
