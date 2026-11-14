package com.riverlifeline.pipeline.domain.model.result;

import java.util.Objects;

/** A target's route to the hub on one pass; the two sizes are null without a route or without coverage. */
public record RouteResult(RouteStatus status, Integer bottleneckM, Integer distanceToWaterM) {
    public RouteResult {
        Objects.requireNonNull(status, "status");
    }
}
