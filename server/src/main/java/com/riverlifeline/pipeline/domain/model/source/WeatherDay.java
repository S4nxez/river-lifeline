package com.riverlifeline.pipeline.domain.model.source;

import java.time.LocalDate;
import java.util.Objects;

/** Daily weather at a region: precipitation, mean temperature and, when known, soil moisture. */
public record WeatherDay(LocalDate day, double precipitationMm, double temperatureC, Double soilMoisture) {
    public WeatherDay {
        Objects.requireNonNull(day, "day");
    }
}
