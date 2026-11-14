package com.riverlifeline.monitoring.infrastructure.adapter.in.web.export;

import com.riverlifeline.monitoring.application.view.Views.PassCommunities;
import com.riverlifeline.monitoring.domain.model.community.Community;
import com.riverlifeline.monitoring.domain.model.community.CommunitySnapshot;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.status.CommunityAssessment;
import com.riverlifeline.shared.domain.GeoPoint;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** The CSV of #13: header row, comma separator, UTF-8, one row per monitored community. */
public final class CsvExport {
    public static final List<String> COLUMNS = List.of(
            "community_id",
            "name",
            "municipality",
            "status",
            "reason",
            "min_width_class_m",
            "route_verified",
            "changed",
            "previous_status",
            "boarding_lon",
            "boarding_lat",
            "pass_id",
            "acquired_at",
            "river_level_m");

    private CsvExport() {}

    public static String write(PassCommunities view) {
        StringBuilder csv = new StringBuilder(String.join(",", COLUMNS)).append("\r\n");
        Pass pass = view.pass();
        for (CommunitySnapshot snapshot : view.communities()) {
            Community community = snapshot.community();
            CommunityAssessment assessment = snapshot.assessment();
            GeoPoint boarding = community.measurementPoint();
            List<String> row = new ArrayList<>(Arrays.asList(
                    community.id().value(),
                    text(community.name()),
                    text(community.municipality()),
                    assessment.status().code(),
                    assessment.reason() == null ? null : assessment.reason().code(),
                    string(assessment.minWidthClassM()),
                    String.valueOf(assessment.routeVerified()),
                    String.valueOf(assessment.changed()),
                    assessment.previousStatus() == null
                            ? null
                            : assessment.previousStatus().code(),
                    String.valueOf(boarding.lon()),
                    String.valueOf(boarding.lat()),
                    pass.id().value(),
                    pass.acquiredAt().toString(),
                    string(pass.riverLevelM())));
            csv.append(String.join(",", row.stream().map(CsvExport::escape).toList()))
                    .append("\r\n");
        }
        return csv.toString();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    /** Free text from OSM: a leading {@code = + @} would run as a formula in a spreadsheet. */
    private static String text(String value) {
        if (value != null && !value.isEmpty() && "=+@\t\r".indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    /** RFC 4180: empty for null, quoted when the value holds a comma, a quote or a line break. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return Objects.toString(value);
    }
}
