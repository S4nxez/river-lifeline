package com.nisarsar.dao.model;

import java.util.List;

/** The API's views of the monitor. Dates are ISO strings (yyyy-MM-dd). */
public final class Connectivity {
    private Connectivity() {}

    public record Place(String id, String name, CommunityKind kind, double lat, double lng) {}

    /** The Sentinel-1 scene that cross-checked a NISAR assessment, and whether it confirms it. */
    public record CrossCheck(String sceneOn, boolean agrees) {}

    public record Assessment(
            String passId, String acquiredOn, ConnectivityStatus status, Integer routeWidthM, CrossCheck sentinel1) {}

    /** A community with its latest known assessment (null if no pass assessed it yet). */
    public record CommunityStatus(
            String id, String name, CommunityKind kind, double lat, double lng, Assessment latest) {}

    public record CommunityHistory(
            String id, String name, CommunityKind kind, double lat, double lng, List<Assessment> assessments) {}

    public record StatusCounts(long connected, long atRisk, long isolated, long noData) {}

    public record PassSummary(
            String id, String mission, String acquiredOn, Integer riverLevelCm, StatusCounts counts) {}

    /** Everything the monitor page shows at first: the hub, the latest pass and every community's status. */
    public record Overview(Place hub, PassSummary latestPass, List<CommunityStatus> communities) {}
}
