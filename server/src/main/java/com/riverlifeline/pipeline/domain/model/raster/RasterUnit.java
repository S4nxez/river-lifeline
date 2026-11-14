package com.riverlifeline.pipeline.domain.model.raster;

/** What a raster's values measure. */
public enum RasterUnit {
    LINEAR_POWER("linear_power"),
    DB("db"),
    PERCENT("percent"),
    CLASS("class");

    private final String wire;

    RasterUnit(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static RasterUnit fromWire(String value) {
        for (RasterUnit unit : values()) {
            if (unit.wire.equals(value)) {
                return unit;
            }
        }
        throw new IllegalArgumentException("Unknown unit: " + value);
    }
}
