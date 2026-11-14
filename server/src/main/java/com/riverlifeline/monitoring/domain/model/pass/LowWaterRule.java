package com.riverlifeline.monitoring.domain.model.pass;

import java.util.List;

/**
 * Below the low-water threshold, NISAR "water" that Sentinel-1 sees as wet mud is not a route. Where Sentinel-1 had
 * no coverage, the route is unverified.
 */
public record LowWaterRule(boolean active, List<String> s1SceneIds, Double unverifiedAreaKm2) {
    public static final LowWaterRule INACTIVE = new LowWaterRule(false, List.of(), null);

    public LowWaterRule {
        s1SceneIds = s1SceneIds == null ? List.of() : List.copyOf(s1SceneIds);
    }
}
