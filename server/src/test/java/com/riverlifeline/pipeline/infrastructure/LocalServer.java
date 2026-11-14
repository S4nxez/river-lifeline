package com.riverlifeline.pipeline.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A local HTTP server for adapter tests: canned answers per path, byte ranges, and a log of every request. */
public final class LocalServer implements AutoCloseable {
    /** One request: method, path, query, Authorization header, Range header and body. */
    public record Request(String method, String path, String query, String authorization, String range, String body) {}

    public interface Handler {
        void handle(HttpExchange exchange, String body) throws IOException;
    }

    private final HttpServer server;
    public final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();

    public LocalServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            URI uri = exchange.getRequestURI();
            requests.add(new Request(
                    exchange.getRequestMethod(),
                    uri.getPath(),
                    uri.getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Range"),
                    body));
            Handler handler = handlers.get(uri.getPath());
            if (handler == null) {
                send(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            } else {
                handler.handle(exchange, body);
            }
            exchange.close();
        });
        server.start();
    }

    /** The address of a path, on 127.0.0.1. */
    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** The address of a path, on localhost: another host name for the same server. */
    public String localhostUrl(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    public LocalServer on(String path, Handler handler) {
        handlers.put(path, handler);
        return this;
    }

    public LocalServer json(String path, String json) {
        return on(path, (ex, body) -> send(ex, 200, "application/json", json.getBytes(StandardCharsets.UTF_8)));
    }

    /** Serves bytes with range support (206 for a Range request). */
    public LocalServer file(String path, byte[] content) {
        return on(path, (ex, body) -> serveRange(ex, content));
    }

    public static void serveRange(HttpExchange ex, byte[] content) throws IOException {
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range == null) {
            send(ex, 200, "application/octet-stream", content);
            return;
        }
        String[] bounds = range.substring("bytes=".length()).split("-");
        int from = Integer.parseInt(bounds[0]);
        int to = Math.min(content.length - 1, Integer.parseInt(bounds[1]));
        byte[] part = java.util.Arrays.copyOfRange(content, from, to + 1);
        ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + content.length);
        send(ex, 206, "application/octet-stream", part);
    }

    public static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    public List<Request> requestsTo(String path) {
        synchronized (requests) {
            return requests.stream().filter(r -> r.path().equals(path)).toList();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
