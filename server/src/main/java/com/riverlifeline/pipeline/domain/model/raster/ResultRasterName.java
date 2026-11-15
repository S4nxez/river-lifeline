package com.riverlifeline.pipeline.domain.model.raster;

/** The rasters a result can declare and upload (contract {@code ResultRasterName}). */
public enum ResultRasterName {
    WATER("water"),
    RADAR("radar"),
    SENTINEL1("sentinel1"),
    SENTINEL1_VV("sentinel1_vv"),
    CHANGE("change");

    private final String wire;

    ResultRasterName(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static ResultRasterName fromWire(String value) {
        for (ResultRasterName name : values()) {
            if (name.wire.equals(value)) {
                return name;
            }
        }
        throw new IllegalArgumentException("Unknown result raster: " + value);
    }
}
