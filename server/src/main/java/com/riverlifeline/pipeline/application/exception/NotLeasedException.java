package com.riverlifeline.pipeline.application.exception;

/** The job is not held by this lease any more (expired, finished, handed to another worker): the worker drops it. */
public class NotLeasedException extends RuntimeException {
    public NotLeasedException(String lease) {
        super("Job " + lease + " is not leased to this worker any more");
    }
}
