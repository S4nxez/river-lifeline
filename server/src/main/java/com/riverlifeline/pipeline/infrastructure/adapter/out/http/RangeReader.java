package com.riverlifeline.pipeline.infrastructure.adapter.out.http;

import java.io.IOException;

/** Random access to the bytes of a remote file. */
public interface RangeReader {
    long size();

    /** Exactly {@code length} bytes at {@code offset}, through a block cache (headers, metadata). */
    byte[] read(long offset, int length) throws IOException;

    /** Exactly {@code length} bytes at {@code offset} in one request, bypassing the cache (bulk data). */
    default byte[] readDirect(long offset, int length) throws IOException {
        return read(offset, length);
    }
}
