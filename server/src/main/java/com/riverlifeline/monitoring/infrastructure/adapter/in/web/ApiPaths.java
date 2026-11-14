package com.riverlifeline.monitoring.infrastructure.adapter.in.web;

/** The routes of contract 2 (CONTRACTS.md 3.3). */
public final class ApiPaths {
    public static final String BASE = "/api/v1";
    public static final String HEALTH = BASE + "/health";
    public static final String AOI = BASE + "/aois/{aoiId}";
    public static final String PASS = AOI + "/passes/{passId}";

    /** {@code application/json; charset=utf-8}, as the contract requires. */
    public static final String JSON = "application/json;charset=UTF-8";

    public static final String SCHEMA_VERSION_HEADER = "X-Schema-Version";

    private ApiPaths() {}

    /** The relative URL of a layer PNG; it always names the pass id, never the alias, so it can be cached forever. */
    public static String layerUrl(String aoiId, String passId, String layerId) {
        return BASE + "/aois/" + aoiId + "/passes/" + passId + "/layers/" + layerId + ".png";
    }
}
