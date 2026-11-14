package com.riverlifeline.pipeline.application.port.in;

/** The backend's recurring work around the worker; the scheduler calls each task on its own interval. */
public interface PipelineTasks {
    /** Searches the passes over every area and records the new ones. */
    void discoverPasses();

    /** Refreshes the daily river level of every area's gauge. */
    void refreshGauges();

    /** Reads the inputs of the next passes to assess and queues their jobs, within the staging budget. */
    void stageAssessments();

    /** Queues a re-evaluation of every pass judged with an earlier version of its area file. */
    void queueReevaluations();

    /** Queues a drought forecast for every region with a trained model. */
    void queueForecasts();

    /** Puts the jobs whose lease ran out back in the queue, or fails them after their last attempt. */
    void expireLeases();

    /** Deletes the raster bodies nothing references any more. */
    void sweepRasters();
}
