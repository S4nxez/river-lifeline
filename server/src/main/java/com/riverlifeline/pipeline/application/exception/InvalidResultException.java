package com.riverlifeline.pipeline.application.exception;

/** A result that does not fit its job: wrong shape, wrong subject, a raster missing or of the wrong length. */
public class InvalidResultException extends RuntimeException {
    public InvalidResultException(String message) {
        super(message);
    }
}
