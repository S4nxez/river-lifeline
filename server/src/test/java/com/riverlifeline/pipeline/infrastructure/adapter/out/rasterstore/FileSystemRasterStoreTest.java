package com.riverlifeline.pipeline.infrastructure.adapter.out.rasterstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.riverlifeline.pipeline.application.port.out.RasterStore;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemRasterStoreTest {
    @TempDir
    Path root;

    @Test
    void aCommittedBodyIsStoredGzippedWithItsLengthAndHash() throws Exception {
        FileSystemRasterStore store = new FileSystemRasterStore(root);
        RasterId id = new RasterId("abcdef");
        byte[] body = new byte[100_000];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i % 7);
        }

        RasterStore.StoredBody stored;
        try (RasterStore.Writer writer = store.create(id)) {
            writer.body().write(body, 0, 40_000);
            writer.body().write(body, 40_000, 60_000);
            stored = writer.commit();
        }

        assertThat(stored.byteLength()).isEqualTo(100_000);
        assertThat(stored.sha256())
                .isEqualTo(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        assertThat(stored.storedBytes()).isLessThan(10_000);
        assertThat(root.resolve("ab").resolve("abcdef.bin.gz")).exists();
        try (InputStream in = new GZIPInputStream(store.openGzip(id).orElseThrow())) {
            assertThat(in.readAllBytes()).isEqualTo(body);
        }
        store.delete(id);
        assertThat(store.openGzip(id)).isEmpty();
    }

    @Test
    void anUncommittedBodyLeavesNothingBehind() throws Exception {
        FileSystemRasterStore store = new FileSystemRasterStore(root);
        RasterId id = new RasterId("xy1");

        try (RasterStore.Writer writer = store.create(id)) {
            writer.body().write(new byte[1000]);
        }

        assertThat(store.openGzip(id)).isEmpty();
        try (var files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }
}
