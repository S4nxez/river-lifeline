package com.riverlifeline.pipeline.infrastructure.adapter.in.schedule;

import com.riverlifeline.pipeline.application.port.in.PipelineTasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the backend's recurring work. Intervals are ISO-8601 durations under {@code river.pipeline.schedule.*}; each
 * task waits for its previous run to end.
 */
public class PipelineScheduler {
    private static final Logger log = LoggerFactory.getLogger(PipelineScheduler.class);

    private final PipelineTasks tasks;

    public PipelineScheduler(PipelineTasks tasks) {
        this.tasks = tasks;
    }

    @Scheduled(
            initialDelayString = "${river.pipeline.schedule.search-delay:PT30S}",
            fixedDelayString = "${river.pipeline.schedule.search:PT6H}")
    public void searchPasses() {
        run("pass search", () -> {
            tasks.discoverPasses();
            tasks.queueReevaluations();
        });
    }

    @Scheduled(
            initialDelayString = "${river.pipeline.schedule.gauge-delay:PT1M}",
            fixedDelayString = "${river.pipeline.schedule.gauge:PT6H}")
    public void refreshGauges() {
        run("gauge refresh", tasks::refreshGauges);
    }

    @Scheduled(
            initialDelayString = "${river.pipeline.schedule.staging-delay:PT2M}",
            fixedDelayString = "${river.pipeline.schedule.staging:PT5M}")
    public void stageAssessments() {
        run("staging", tasks::stageAssessments);
    }

    @Scheduled(
            initialDelayString = "${river.pipeline.schedule.forecast-delay:PT10M}",
            fixedDelayString = "${river.pipeline.schedule.forecast:P1D}")
    public void queueForecasts() {
        run("drought forecasts", tasks::queueForecasts);
    }

    @Scheduled(
            initialDelayString = "${river.pipeline.schedule.leases-delay:PT1M}",
            fixedDelayString = "${river.pipeline.schedule.leases:PT1M}")
    public void expireLeases() {
        run("lease expiry", tasks::expireLeases);
    }

    @Scheduled(
            initialDelayString = "${river.pipeline.schedule.sweep-delay:PT5M}",
            fixedDelayString = "${river.pipeline.schedule.sweep:PT15M}")
    public void sweepRasters() {
        run("raster sweep", tasks::sweepRasters);
    }

    private static void run(String what, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("The {} failed", what, e);
        }
    }
}
