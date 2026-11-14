package com.riverlifeline.pipeline.application.exception;

/** The inputs of a job could not be prepared; {@code permanent} when trying again would fail the same way. */
public class StagingException extends RuntimeException {
    private final boolean permanent;

    public StagingException(String message, boolean permanent) {
        super(message);
        this.permanent = permanent;
    }

    public boolean permanent() {
        return permanent;
    }
}
