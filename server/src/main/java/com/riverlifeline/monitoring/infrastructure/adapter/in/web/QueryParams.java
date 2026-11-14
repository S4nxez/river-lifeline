package com.riverlifeline.monitoring.infrastructure.adapter.in.web;

import com.riverlifeline.monitoring.application.exception.InvalidParameterException;
import com.riverlifeline.monitoring.domain.model.community.CommunityFilter;
import com.riverlifeline.monitoring.domain.model.community.CommunitySort;
import com.riverlifeline.monitoring.domain.model.status.Status;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses query parameters strictly: anything that can't be used is a 400 {@code invalid_parameter}. */
final class QueryParams {
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private QueryParams() {}

    /** {@code YYYY-MM-DD}, or null when absent. */
    static LocalDate date(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!DATE.matcher(value).matches()) {
            throw new InvalidParameterException(
                    name, "'" + name + "' must be a date as YYYY-MM-DD, got '" + value + "'.");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidParameterException(name, "'" + name + "' is not a valid date: '" + value + "'.");
        }
    }

    /** {@code true} / {@code false} (also {@code 1} / {@code 0}), or null when absent. */
    static Boolean bool(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default ->
                throw new InvalidParameterException(name, "'" + name + "' must be true or false, got '" + value + "'.");
        };
    }

    /** A comma list of status codes, e.g. {@code isolated,at_risk}. */
    static Set<Status> statuses(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<Status> statuses = EnumSet.noneOf(Status.class);
        for (String code : value.split(",")) {
            if (code.isBlank()) {
                continue;
            }
            statuses.add(Status.fromCode(code.strip())
                    .orElseThrow(() -> new InvalidParameterException(
                            "status",
                            "Unknown status '" + code.strip() + "'; expected "
                                    + Arrays.stream(Status.values())
                                            .map(Status::code)
                                            .toList() + ".")));
        }
        return statuses;
    }

    static CommunitySort sort(String value) {
        if (value == null || value.isBlank()) {
            return CommunitySort.STATUS;
        }
        return CommunitySort.fromCode(value)
                .orElseThrow(() -> new InvalidParameterException(
                        "sort", "'sort' must be status, name or width, got '" + value + "'."));
    }

    static CommunityFilter filter(String status, String changed, String query) {
        return new CommunityFilter(statuses(status), Boolean.TRUE.equals(bool("changed", changed)), query);
    }
}
