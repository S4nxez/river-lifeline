package com.riverlifeline.pipeline.domain.model.result;

/** A community's whole-record class. */
public enum CommunityClass {
    MONITORED("monitored"),
    UNRESOLVED("unresolved"),
    INLAND("inland"),
    NO_DATA("no_data");

    private final String wire;

    CommunityClass(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static CommunityClass fromWire(String value) {
        for (CommunityClass c : values()) {
            if (c.wire.equals(value)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unknown community class: " + value);
    }
}
