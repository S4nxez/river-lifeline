package com.riverlifeline.pipeline.application.service;

import java.time.Duration;
import java.util.Objects;

/**
 * Tuning of the job queue and of staging.
 *
 * @param lease how long a worker holds a job without a heartbeat
 * @param maxAttempts hand-outs of a job before it is marked failed
 * @param stagedJobs most {@code assess_pass} jobs with their inputs on disk at once
 * @param stagingRetry wait after a failed staging or a failed {@code assess_pass} before trying the pass again
 * @param forecastHorizonDays horizon of the drought forecasts the backend queues
 * @param rasterGrace age before an unreferenced raster body may be deleted (covers jobs being created)
 */
public record PipelineSettings(
        Duration lease,
        int maxAttempts,
        int stagedJobs,
        Duration stagingRetry,
        int forecastHorizonDays,
        Duration rasterGrace) {

    public PipelineSettings {
        Objects.requireNonNull(lease, "lease");
        if (lease.toSeconds() < 1 || maxAttempts < 1 || stagedJobs < 1 || forecastHorizonDays < 1) {
            throw new IllegalArgumentException(
                    "lease, maxAttempts, stagedJobs and forecastHorizonDays must be positive");
        }
        Objects.requireNonNull(stagingRetry, "stagingRetry");
        Objects.requireNonNull(rasterGrace, "rasterGrace");
    }

    public int leaseSeconds() {
        return (int) lease.toSeconds();
    }
}
