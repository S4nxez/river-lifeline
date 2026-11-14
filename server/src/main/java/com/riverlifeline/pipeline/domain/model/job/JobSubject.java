package com.riverlifeline.pipeline.domain.model.job;

import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.pass.PassId;
import java.time.LocalDate;

/**
 * What a job is about, as one string: at most one unfinished-and-waiting job exists per type and subject, and a result
 * must be about the job's subject.
 */
public final class JobSubject {
    private JobSubject() {}

    public static String pass(AreaId area, PassId pass) {
        return area + "/" + pass;
    }

    public static String change(AreaId area, PassId pass, PassId since) {
        return area + "/" + pass + "/" + since;
    }

    public static String area(AreaId area) {
        return area.value();
    }

    public static String region(String region) {
        return region;
    }

    public static String forecast(String region, LocalDate asOf, int horizonDays) {
        return region + "/" + asOf + "/" + horizonDays;
    }
}
