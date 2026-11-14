package com.riverlifeline.monitoring.application.exception;

/** A query parameter a client sent can't be used. */
public class InvalidParameterException extends RuntimeException {
    private final String parameter;

    public InvalidParameterException(String parameter, String message) {
        super(message);
        this.parameter = parameter;
    }

    public String parameter() {
        return parameter;
    }
}
