package com.riverlifeline.pipeline.infrastructure.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riverlifeline.pipeline.infrastructure.LocalServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpRangeChannelTest {
    private final HttpClient client =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final byte[] content = new byte[10_000];
    private LocalServer server;
    private final AtomicInteger refusals = new AtomicInteger();

    @BeforeEach
    void serve() throws IOException {
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }
        server = new LocalServer();
        String signed = server.url("/signed/product.h5");
        server.on("/product.h5", (ex, body) -> {
            if (!"Bearer secret".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                LocalServer.send(ex, 401, "text/plain", new byte[0]);
                return;
            }
            ex.getResponseHeaders().add("Location", signed);
            LocalServer.send(ex, 303, "text/plain", new byte[0]);
        });
        server.on("/signed/product.h5", (ex, body) -> {
            String range = ex.getRequestHeaders().getFirst("Range");
            if (!"bytes=0-0".equals(range) && refusals.getAndDecrement() > 0) {
                LocalServer.send(ex, 403, "text/plain", new byte[0]);
                return;
            }
            LocalServer.serveRange(ex, content);
        });
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpRangeChannel open(String token) throws IOException {
        return new HttpRangeChannel(client, URI.create(server.localhostUrl("/product.h5")), token, "test", 1024, 2);
    }

    @Test
    void theTokenGoesToTheProductHostOnlyAndRangesAreRead() throws IOException {
        try (HttpRangeChannel channel = open("secret")) {
            assertThat(channel.size()).isEqualTo(10_000);
            assertThat(channel.read(1500, 100)).isEqualTo(java.util.Arrays.copyOfRange(content, 1500, 1600));
            assertThat(channel.readDirect(9_990, 10)).isEqualTo(java.util.Arrays.copyOfRange(content, 9_990, 10_000));
            channel.position(9_998);
            ByteBuffer two = ByteBuffer.allocate(5);
            assertThat(channel.read(two)).isEqualTo(2);
            assertThat(channel.read(ByteBuffer.allocate(1))).isEqualTo(-1);
            assertThatThrownBy(() -> channel.read(9_999, 2)).isInstanceOf(IOException.class);
        }
        assertThat(server.requestsTo("/product.h5")).allMatch(r -> "Bearer secret".equals(r.authorization()));
        assertThat(server.requestsTo("/signed/product.h5")).allMatch(r -> r.authorization() == null);
    }

    @Test
    void blocksAreCachedAndAnExpiredSignatureIsRenewed() throws IOException {
        try (HttpRangeChannel channel = open("secret")) {
            channel.read(0, 10);
            channel.read(500, 10);
            assertThat(server.requestsTo("/signed/product.h5")).hasSize(2);
            refusals.set(1);
            assertThat(channel.read(5000, 10)).isEqualTo(java.util.Arrays.copyOfRange(content, 5000, 5010));
        }
        assertThat(server.requestsTo("/product.h5")).hasSize(2);
    }

    @Test
    void aRefusedTokenIsReported() {
        assertThatThrownBy(() -> open("wrong")).isInstanceOf(IOException.class).hasMessageContaining("401");
    }
}
