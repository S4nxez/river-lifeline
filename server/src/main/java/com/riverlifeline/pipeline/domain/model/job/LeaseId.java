package com.riverlifeline.pipeline.domain.model.job;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * One hand-out of a job to a worker: the {@code Job.id} the worker sees. A job handed out again gets a new lease id,
 * so calls with an earlier one are recognised and answered 409.
 */
public record LeaseId(String value) {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final SecureRandom RANDOM = new SecureRandom();

    public LeaseId {
        if (!isValid(value)) {
            throw new IllegalArgumentException("Invalid lease id: " + value);
        }
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    public static LeaseId random() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return new LeaseId(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    @Override
    public String toString() {
        return value;
    }
}
