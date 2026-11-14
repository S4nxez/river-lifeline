package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.application.exception.InvalidResultException;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.result.JobResult;

/** Reads a posted result ({@code data}) as the result of a job type. */
public interface JobResultDecoder {
    /** @throws InvalidResultException when the JSON is not a result of that type */
    JobResult decode(JobType type, String dataJson);
}
