package com.riverlifeline.pipeline.domain.model.result;

/** A route's status on one pass (contract names). */
public enum RouteStatus {
    CONNECTED("connected"),
    AT_RISK("at_risk"),
    ISOLATED("isolated"),
    NO_DATA("no_data");

    private final String wire;

    RouteStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static RouteStatus fromWire(String value) {
        for (RouteStatus status : values()) {
            if (status.wire.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown route status: " + value);
    }
}
