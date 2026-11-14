package com.riverlifeline.monitoring.domain.model.riverlevel;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** Daily river levels at the AOI's gauge, oldest first. Missing days are simply absent. */
public record RiverLevelSeries(
        String provider, String stationCode, String stationName, String unit, List<RiverLevelReading> readings) {

    public RiverLevelSeries {
        readings = readings == null
                ? List.of()
                : readings.stream()
                        .sorted(Comparator.comparing(RiverLevelReading::date))
                        .toList();
    }

    /** The readings between both days, inclusive; a null bound is open. */
    public List<RiverLevelReading> between(LocalDate from, LocalDate to) {
        return readings.stream()
                .filter(reading -> from == null || !reading.date().isBefore(from))
                .filter(reading -> to == null || !reading.date().isAfter(to))
                .toList();
    }
}
