package com.riverlifeline.pipeline.domain.model.raster;

/** Value types a raw raster body can hold, little-endian. */
public enum DataType {
    UINT8("uint8", 1),
    INT16("int16", 2),
    FLOAT32("float32", 4);

    private final String wire;
    private final int bytes;

    DataType(String wire, int bytes) {
        this.wire = wire;
        this.bytes = bytes;
    }

    public String wire() {
        return wire;
    }

    public int bytes() {
        return bytes;
    }

    public static DataType fromWire(String value) {
        for (DataType type : values()) {
            if (type.wire.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown dtype: " + value);
    }
}
