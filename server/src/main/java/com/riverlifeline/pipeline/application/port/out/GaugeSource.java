package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.domain.model.source.GaugeReading;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** Telemetry readings of an official river gauge (ANA). */
public interface GaugeSource {
    List<GaugeReading> readings(String station, LocalDate from, LocalDate to) throws IOException;
}
