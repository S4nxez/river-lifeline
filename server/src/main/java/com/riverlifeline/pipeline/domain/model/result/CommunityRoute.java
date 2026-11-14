package com.riverlifeline.pipeline.domain.model.result;

import java.util.Objects;

/** A community's route to the hub on one pass. */
public record CommunityRoute(String communityId, RouteResult route) {
    public CommunityRoute {
        if (communityId == null || communityId.isBlank()) {
            throw new IllegalArgumentException("A community route needs a community id");
        }
        Objects.requireNonNull(route, "route");
    }
}
