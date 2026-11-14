package com.riverlifeline.pipeline.domain.model.job;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** The backend's id of a job, never shown to the worker (it sees the id of its lease). */
public record JobId(String value) {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final SecureRandom RANDOM = new SecureRandom();

    public JobId {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid job id: " + value);
        }
    }

    public static JobId random() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return new JobId(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    @Override
    public String toString() {
        return value;
    }
}
