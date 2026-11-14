package com.riverlifeline.pipeline.domain.model.job;

/** Where a job is in its life: waiting for a worker, held by one, or finished. */
public enum JobState {
    PENDING("pending"),
    LEASED("leased"),
    DONE("done"),
    FAILED("failed");

    private final String wire;

    JobState(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static JobState fromWire(String value) {
        for (JobState state : values()) {
            if (state.wire.equals(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown job state: " + value);
    }
}
