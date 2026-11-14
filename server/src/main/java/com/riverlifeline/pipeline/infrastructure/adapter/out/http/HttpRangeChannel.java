package com.riverlifeline.pipeline.infrastructure.adapter.out.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A remote file read through HTTP range requests, as a seekable channel (for jHDF) and as a {@link RangeReader}.
 * Blocks are kept in a small LRU cache. Redirects are followed by hand, so the bearer token is only sent to the host
 * it was given for and never to the CDN the product redirects to; the signed location is signed again when it
 * expires (401/403).
 */
public final class HttpRangeChannel implements SeekableByteChannel, RangeReader {
    private static final int ATTEMPTS = 4;
    private static final int MAX_REDIRECTS = 8;

    private final HttpClient http;
    private final URI source;
    private final String bearer;
    private final String userAgent;
    private final int blockSize;
    private final Map<Long, byte[]> cache;
    private URI signed;
    private long size;
    private long position;
    private boolean open = true;
    private long downloaded;

    public HttpRangeChannel(
            HttpClient http, URI source, String bearer, String userAgent, int blockSize, int cachedBlocks)
            throws IOException {
        this.http = http;
        this.source = source;
        this.bearer = bearer == null || bearer.isBlank() ? null : bearer.strip();
        this.userAgent = userAgent;
        this.blockSize = blockSize;
        this.cache = new LinkedHashMap<>(cachedBlocks * 2, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                return size() > cachedBlocks;
            }
        };
        sign();
    }

    private void sign() throws IOException {
        URI at = source;
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(at)
                    .timeout(Duration.ofSeconds(90))
                    .header("User-Agent", userAgent)
                    .header("Range", "bytes=0-0");
            if (bearer != null && at.getHost() != null && at.getHost().equalsIgnoreCase(source.getHost())) {
                request.header("Authorization", "Bearer " + bearer);
            }
            HttpResponse<byte[]> response = send(request.build());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                at = at.resolve(response.headers()
                        .firstValue("Location")
                        .orElseThrow(() -> new IOException("Redirect without a location from " + source.getHost())));
                continue;
            }
            if (status == 401 || status == 403) {
                throw new IOException("Access refused (HTTP " + status + ") by " + at.getHost()
                        + (bearer == null ? "" : ": check that the token is valid"));
            }
            if (status != 206) {
                throw new IOException("Expected a partial response from " + at.getHost() + ", got HTTP " + status);
            }
            String range = response.headers()
                    .firstValue("Content-Range")
                    .orElseThrow(() -> new IOException("No Content-Range from " + source.getHost()));
            size = Long.parseLong(range.substring(range.indexOf('/') + 1).strip());
            signed = at;
            return;
        }
        throw new IOException("Too many redirects from " + source.getHost());
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private byte[] fetch(long from, long to) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                HttpResponse<byte[]> response = send(HttpRequest.newBuilder(signed)
                        .timeout(Duration.ofSeconds(180))
                        .header("User-Agent", userAgent)
                        .header("Range", "bytes=" + from + "-" + to)
                        .build());
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    last = new IOException("HTTP " + response.statusCode() + " (signed location expired)");
                    sign();
                    continue;
                }
                if (response.statusCode() != 206 || response.body().length != to - from + 1) {
                    throw new IOException("HTTP " + response.statusCode() + ", " + response.body().length + " of "
                            + (to - from + 1) + " bytes");
                }
                downloaded += response.body().length;
                return response.body();
            } catch (IOException e) {
                last = e;
                pause(attempt);
            }
        }
        throw new IOException(
                "Could not read bytes " + from + "-" + to + " of " + source.getHost() + source.getPath(), last);
    }

    private static void pause(int attempt) throws IOException {
        try {
            Thread.sleep(500L * (1L << attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private byte[] block(long index) throws IOException {
        byte[] cached = cache.get(index);
        if (cached != null) {
            return cached;
        }
        long from = index * blockSize;
        byte[] data = fetch(from, Math.min(size, from + blockSize) - 1);
        cache.put(index, data);
        return data;
    }

    @Override
    public synchronized byte[] read(long offset, int length) throws IOException {
        if (offset < 0 || offset + length > size) {
            throw new IOException("Read past the end: " + offset + "+" + length + " of " + size);
        }
        byte[] out = new byte[length];
        int done = 0;
        while (done < length) {
            long at = offset + done;
            byte[] block = block(at / blockSize);
            int within = (int) (at % blockSize);
            int n = Math.min(length - done, block.length - within);
            System.arraycopy(block, within, out, done, n);
            done += n;
        }
        return out;
    }

    @Override
    public synchronized byte[] readDirect(long offset, int length) throws IOException {
        if (length == 0) {
            return new byte[0];
        }
        if (offset < 0 || offset + length > size) {
            throw new IOException("Read past the end: " + offset + "+" + length + " of " + size);
        }
        return fetch(offset, offset + length - 1);
    }

    /** Bytes actually downloaded so far. */
    public long downloaded() {
        return downloaded;
    }

    @Override
    public synchronized int read(ByteBuffer dst) throws IOException {
        if (position >= size) {
            return -1;
        }
        int n = (int) Math.min(dst.remaining(), size - position);
        dst.put(read(position, n));
        position += n;
        return n;
    }

    @Override
    public int write(ByteBuffer src) {
        throw new NonWritableChannelException();
    }

    @Override
    public synchronized long position() {
        return position;
    }

    @Override
    public synchronized SeekableByteChannel position(long newPosition) {
        position = newPosition;
        return this;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public SeekableByteChannel truncate(long newSize) {
        throw new NonWritableChannelException();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public synchronized void close() {
        open = false;
        cache.clear();
    }
}
