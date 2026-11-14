package com.riverlifeline.monitoring.application.port.out;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** A file to stream to the client without loading it in memory. */
public record BinaryContent(long sizeBytes, Opener opener) {
    public BinaryContent {
        Objects.requireNonNull(opener, "opener");
    }

    public InputStream open() throws IOException {
        return opener.open();
    }

    @FunctionalInterface
    public interface Opener {
        InputStream open() throws IOException;
    }
}
