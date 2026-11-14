package com.riverlifeline.pipeline.application.exception;

/** A worker request with a malformed parameter. */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
