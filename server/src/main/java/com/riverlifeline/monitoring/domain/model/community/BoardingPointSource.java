package com.riverlifeline.monitoring.domain.model.community;

import java.util.Arrays;
import java.util.Optional;

/** Where the boarding point comes from: an OSM feature, or the village centre as a fallback. */
public enum BoardingPointSource {
    OSM_PIER("osm_pier"),
    OSM_FERRY_TERMINAL("osm_ferry_terminal"),
    OSM_PORT("osm_port"),
    OSM_SLIPWAY("osm_slipway"),
    VILLAGE_CENTER("village_center");

    private final String code;

    BoardingPointSource(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<BoardingPointSource> fromCode(String code) {
        return Arrays.stream(values())
                .filter(source -> source.code.equals(code))
                .findFirst();
    }
}
