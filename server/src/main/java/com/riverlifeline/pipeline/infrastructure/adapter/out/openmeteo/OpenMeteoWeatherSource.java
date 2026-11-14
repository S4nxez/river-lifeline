package com.riverlifeline.pipeline.infrastructure.adapter.out.openmeteo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riverlifeline.pipeline.application.port.out.WeatherSource;
import com.riverlifeline.pipeline.domain.model.source.WeatherDay;
import com.riverlifeline.pipeline.infrastructure.adapter.out.http.HttpCalls;
import com.riverlifeline.shared.domain.GeoPoint;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Daily observed weather from the Open-Meteo historical archive (ERA5 reanalysis): precipitation sum, mean 2 m
 * temperature and mean soil moisture of the top 7 cm. Days without precipitation or temperature are left out.
 */
public class OpenMeteoWeatherSource implements WeatherSource {
    static final String DAILY = "precipitation_sum,temperature_2m_mean,soil_moisture_0_to_7cm_mean";

    private final HttpCalls http;
    private final ObjectMapper json;
    private final String archiveUrl;

    public OpenMeteoWeatherSource(HttpCalls http, ObjectMapper json, String archiveUrl) {
        this.http = http;
        this.json = json;
        this.archiveUrl = archiveUrl;
    }

    @Override
    public List<WeatherDay> daily(GeoPoint at, LocalDate from, LocalDate to) throws IOException {
        URI uri = URI.create(String.format(
                Locale.ROOT,
                "%s?latitude=%.4f&longitude=%.4f&start_date=%s&end_date=%s&daily=%s&timezone=UTC",
                archiveUrl,
                at.lat(),
                at.lon(),
                from,
                to,
                DAILY));
        return parse(json.readTree(http.get(uri)));
    }

    static List<WeatherDay> parse(JsonNode answer) {
        JsonNode daily = answer.path("daily");
        JsonNode days = daily.path("time");
        JsonNode rain = daily.path("precipitation_sum");
        JsonNode temperature = daily.path("temperature_2m_mean");
        JsonNode soil = daily.path("soil_moisture_0_to_7cm_mean");
        List<WeatherDay> out = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            JsonNode r = rain.get(i);
            JsonNode t = temperature.get(i);
            if (r == null || r.isNull() || t == null || t.isNull()) {
                continue;
            }
            JsonNode s = soil.get(i);
            out.add(new WeatherDay(
                    LocalDate.parse(days.get(i).asText()),
                    r.asDouble(),
                    t.asDouble(),
                    s == null || s.isNull() ? null : s.asDouble()));
        }
        return out;
    }
}
