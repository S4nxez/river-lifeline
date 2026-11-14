package com.riverlifeline.monitoring.domain.model;

import java.util.regex.Pattern;

/** The contract's id rule (CONTRACTS.md 1.2): lowercase and URL-safe, {@code [a-z0-9-]}. */
public final class Identifier {
    private static final Pattern URL_SAFE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private Identifier() {}

    public static boolean isValid(String value) {
        return value != null && value.length() <= 200 && URL_SAFE.matcher(value).matches();
    }

    public static String require(String value, String what) {
        if (!isValid(value)) {
            throw new IllegalArgumentException("Invalid " + what + " id: " + value);
        }
        return value;
    }
}
