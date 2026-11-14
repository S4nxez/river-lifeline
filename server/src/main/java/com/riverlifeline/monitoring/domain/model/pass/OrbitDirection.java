package com.riverlifeline.monitoring.domain.model.pass;

import java.util.Arrays;
import java.util.Optional;

public enum OrbitDirection {
    ASCENDING("ascending"),
    DESCENDING("descending");

    private final String code;

    OrbitDirection(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<OrbitDirection> fromCode(String code) {
        return Arrays.stream(values())
                .filter(direction -> direction.code.equals(code))
                .findFirst();
    }
}
