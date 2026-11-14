package com.riverlifeline.pipeline.infrastructure.adapter.out.rasterstore;

import com.riverlifeline.pipeline.application.port.out.RasterStore;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Raster bodies as gzip files under one root: {@code <first two characters of the id>/<id>.bin.gz}. A body is written
 * to a temporary file next to its place and renamed when complete, so a reader never sees half a body.
 */
public class FileSystemRasterStore implements RasterStore {
    private final Path root;

    public FileSystemRasterStore(Path root) throws IOException {
        this.root = Files.createDirectories(root).toAbsolutePath();
    }

    Path path(RasterId id) {
        String value = id.value();
        return root.resolve(value.substring(0, Math.min(2, value.length()))).resolve(value + ".bin.gz");
    }

    @Override
    public Writer create(RasterId id) throws IOException {
        Path target = path(id);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(id.value() + ".tmp-" + UUID.randomUUID());
        return new FileWriter(target, temporary);
    }

    @Override
    public Optional<InputStream> openGzip(RasterId id) throws IOException {
        Path file = path(id);
        return Files.isRegularFile(file) ? Optional.of(Files.newInputStream(file)) : Optional.empty();
    }

    @Override
    public void delete(RasterId id) throws IOException {
        Files.deleteIfExists(path(id));
    }

    private static final class FileWriter implements Writer {
        private final Path target;
        private final Path temporary;
        private final GZIPOutputStream gzip;
        private final Counting raw;
        private boolean committed;
        private boolean closed;

        FileWriter(Path target, Path temporary) throws IOException {
            this.target = target;
            this.temporary = temporary;
            this.gzip =
                    new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary), 1 << 16), 1 << 16);
            this.raw = new Counting(gzip);
        }

        @Override
        public OutputStream body() {
            return raw;
        }

        @Override
        public StoredBody commit() throws IOException {
            raw.flush();
            gzip.finish();
            gzip.close();
            closed = true;
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            committed = true;
            return new StoredBody(raw.count, HexFormat.of().formatHex(raw.digest.digest()), Files.size(target));
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                try {
                    gzip.close();
                } finally {
                    if (!committed) {
                        Files.deleteIfExists(temporary);
                    }
                }
            } else if (!committed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /** Counts and hashes the raw bytes on their way to the gzip stream. */
    private static final class Counting extends FilterOutputStream {
        private final MessageDigest digest;
        private long count;

        Counting(OutputStream out) {
            super(out);
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            digest.update((byte) b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            digest.update(b, off, len);
            count += len;
        }

        @Override
        public void close() {
            // The writer closes the gzip stream itself
        }
    }
}
