package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.domain.model.source.GaugeDay;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Daily river levels per gauge station. */
public interface GaugeRepository {
    /** Stores days, replacing the levels already stored for them. */
    void save(String station, List<GaugeDay> days);

    /** The stored days between two dates, both included, oldest first. */
    List<GaugeDay> between(String station, LocalDate from, LocalDate to);

    Optional<LocalDate> latestDay(String station);
}
