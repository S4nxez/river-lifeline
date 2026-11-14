package com.riverlifeline.pipeline.domain.model.raster;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Id of a stored raster body, URL-safe ({@code [A-Za-z0-9_-]{1,128}}), part of the worker's download path. */
public record RasterId(String value) {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final SecureRandom RANDOM = new SecureRandom();

    public RasterId {
        if (!isValid(value)) {
            throw new IllegalArgumentException("Invalid raster id: " + value);
        }
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    public static RasterId random() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return new RasterId(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    @Override
    public String toString() {
        return value;
    }
}
