package com.riverlifeline.monitoring.domain.exception;

/** Base of the domain's failures; the web adapter turns each subclass into its HTTP status and error code. */
public abstract class MonitoringException extends RuntimeException {
    protected MonitoringException(String message) {
        super(message);
    }

    protected MonitoringException(String message, Throwable cause) {
        super(message, cause);
    }
}
