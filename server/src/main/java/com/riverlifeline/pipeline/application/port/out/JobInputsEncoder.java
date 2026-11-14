package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.domain.model.job.JobInputs;

/** Writes a job's inputs as the contract's JSON. */
public interface JobInputsEncoder {
    String encode(JobInputs inputs);
}
