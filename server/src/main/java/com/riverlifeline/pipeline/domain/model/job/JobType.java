package com.riverlifeline.pipeline.domain.model.job;

/** The kinds of job the worker runs (contract {@code JobType}). */
public enum JobType {
    ASSESS_PASS("assess_pass"),
    REEVALUATE_PASS("reevaluate_pass"),
    DETECT_CHANGE("detect_change"),
    CLASSIFY_COMMUNITIES("classify_communities"),
    TRAIN_DROUGHT_MODEL("train_drought_model"),
    FORECAST_DROUGHT("forecast_drought");

    private final String wire;

    JobType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** The type with this wire name, or null when the backend does not know it. */
    public static JobType fromWireOrNull(String value) {
        for (JobType type : values()) {
            if (type.wire.equals(value)) {
                return type;
            }
        }
        return null;
    }

    public static JobType fromWire(String value) {
        JobType type = fromWireOrNull(value);
        if (type == null) {
            throw new IllegalArgumentException("Unknown job type: " + value);
        }
        return type;
    }
}
