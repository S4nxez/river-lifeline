package com.riverlifeline.monitoring.domain.model.pass;

/**
 * How many monitored communities are in each status on a pass. On a pass that was not evaluated every count but
 * {@code monitored} is null.
 */
public record StatusCounts(
        Integer monitored, Integer connected, Integer atRisk, Integer isolated, Integer noData, Integer changed) {

    public static StatusCounts notEvaluated(Integer monitored) {
        return new StatusCounts(monitored, null, null, null, null, null);
    }
}
