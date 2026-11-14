package com.riverlifeline.monitoring.domain.model.community;

import java.util.Arrays;
import java.util.Optional;

/** Why a community is not monitored at all. */
public enum ExclusionReason {
    TERRA_FIRME("terra_firme"),
    NEVER_NAVIGABLE("never_navigable"),
    NO_BOARDING_POINT("no_boarding_point");

    private final String code;

    ExclusionReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<ExclusionReason> fromCode(String code) {
        return Arrays.stream(values())
                .filter(reason -> reason.code.equals(code))
                .findFirst();
    }
}
