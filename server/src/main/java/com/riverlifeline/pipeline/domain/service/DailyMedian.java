package com.riverlifeline.pipeline.domain.service;

import com.riverlifeline.pipeline.domain.model.source.GaugeDay;
import com.riverlifeline.pipeline.domain.model.source.GaugeReading;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The daily median level of a gauge's telemetry readings: the value the worker's method compares between days. */
public final class DailyMedian {
    private DailyMedian() {}

    public static List<GaugeDay> of(List<GaugeReading> readings) {
        Map<LocalDate, List<Double>> byDay = new TreeMap<>();
        for (GaugeReading reading : readings) {
            if (Double.isFinite(reading.levelCm())) {
                byDay.computeIfAbsent(reading.day(), d -> new ArrayList<>()).add(reading.levelCm());
            }
        }
        List<GaugeDay> days = new ArrayList<>();
        byDay.forEach((day, levels) -> {
            levels.sort(Double::compare);
            int n = levels.size();
            double median = n % 2 == 1 ? levels.get(n / 2) : (levels.get(n / 2 - 1) + levels.get(n / 2)) / 2;
            days.add(new GaugeDay(day, Math.round(median * 10) / 10.0));
        });
        return days;
    }
}
