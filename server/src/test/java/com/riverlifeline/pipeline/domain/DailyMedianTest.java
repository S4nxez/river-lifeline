package com.riverlifeline.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.riverlifeline.pipeline.domain.model.source.GaugeDay;
import com.riverlifeline.pipeline.domain.model.source.GaugeReading;
import com.riverlifeline.pipeline.domain.service.DailyMedian;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailyMedianTest {
    @Test
    void eachDayGetsTheMedianOfItsReadings() {
        LocalDate d1 = LocalDate.of(2026, 11, 1);
        LocalDate d2 = LocalDate.of(2026, 11, 2);

        List<GaugeDay> days = DailyMedian.of(List.of(
                new GaugeReading(d2, 820),
                new GaugeReading(d1, 830),
                new GaugeReading(d1, 900),
                new GaugeReading(d1, 831),
                new GaugeReading(d2, 823),
                new GaugeReading(d2, Double.NaN)));

        assertThat(days).containsExactly(new GaugeDay(d1, 831), new GaugeDay(d2, 821.5));
    }
}
