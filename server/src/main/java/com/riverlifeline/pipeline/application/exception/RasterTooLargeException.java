package com.riverlifeline.pipeline.application.exception;

/** An uploaded raster body longer than the job's area grid allows. */
public class RasterTooLargeException extends RuntimeException {
    public RasterTooLargeException(long limit) {
        super("Raster body larger than " + limit + " bytes");
    }
}
