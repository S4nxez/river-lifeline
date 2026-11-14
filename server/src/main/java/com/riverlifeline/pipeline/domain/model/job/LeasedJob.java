package com.riverlifeline.pipeline.domain.model.job;

import java.util.Objects;

/** A job handed out to a worker, with its inputs already written as the contract's JSON. */
public record LeasedJob(LeaseId lease, JobType type, int contractVersion, int leaseSeconds, String inputsJson) {
    public LeasedJob {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(inputsJson, "inputsJson");
    }
}
