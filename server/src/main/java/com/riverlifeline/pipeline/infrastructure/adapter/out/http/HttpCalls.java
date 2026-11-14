package com.riverlifeline.pipeline.infrastructure.adapter.out.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Plain GET and POST calls to the external services, retried on network errors and 5xx answers. */
public final class HttpCalls {
    private static final int ATTEMPTS = 3;

    private final HttpClient http;
    private final String userAgent;
    private final Duration timeout;

    public HttpCalls(HttpClient http, String userAgent, Duration timeout) {
        this.http = http;
        this.userAgent = userAgent;
        this.timeout = timeout;
    }

    public HttpClient client() {
        return http;
    }

    public String userAgent() {
        return userAgent;
    }

    public byte[] get(URI uri) throws IOException {
        return send(HttpRequest.newBuilder(uri).GET());
    }

    public byte[] postJson(URI uri, String body) throws IOException {
        return send(HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private byte[] send(HttpRequest.Builder builder) throws IOException {
        HttpRequest request =
                builder.timeout(timeout).header("User-Agent", userAgent).build();
        IOException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            HttpResponse<byte[]> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            } catch (IOException e) {
                response = null;
                last = e;
            }
            if (response != null) {
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                last = new IOException(
                        "HTTP " + status + " from " + request.uri().getHost());
                if (status < 500 && status != 429) {
                    throw last;
                }
            }
            try {
                Thread.sleep(1000L * (1L << attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        }
        throw last;
    }
}
