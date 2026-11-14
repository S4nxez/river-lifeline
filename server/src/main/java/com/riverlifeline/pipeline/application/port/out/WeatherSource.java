package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.domain.model.source.WeatherDay;
import com.riverlifeline.shared.domain.GeoPoint;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** Daily observed weather at a point (Open-Meteo archive). */
public interface WeatherSource {
    List<WeatherDay> daily(GeoPoint at, LocalDate from, LocalDate to) throws IOException;
}
