package com.riverlifeline.monitoring.domain.model.pass;

import java.util.Arrays;
import java.util.Optional;

/** The image layers of a pass, in drawing order, with how the map shows them by default. */
public enum LayerId {
    RADAR_HH("radar_hh", "Radar (HH)", true, 1.0),
    SENTINEL1_VV("sentinel1_vv", "Sentinel-1 (VV)", false, 1.0),
    WATER("water", "Open water", true, 0.8),
    FLOODED_VEGETATION("flooded_vegetation", "Water under vegetation", true, 0.8),
    CHANGE("change", "Change vs previous pass", false, 0.9),
    NODATA("nodata", "No data", true, 1.0);

    private final String code;
    private final String label;
    private final boolean defaultVisible;
    private final double defaultOpacity;

    LayerId(String code, String label, boolean defaultVisible, double defaultOpacity) {
        this.code = code;
        this.label = label;
        this.defaultVisible = defaultVisible;
        this.defaultOpacity = defaultOpacity;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public boolean defaultVisible() {
        return defaultVisible;
    }

    public double defaultOpacity() {
        return defaultOpacity;
    }

    public static Optional<LayerId> fromCode(String code) {
        return Arrays.stream(values()).filter(layer -> layer.code.equals(code)).findFirst();
    }
}
