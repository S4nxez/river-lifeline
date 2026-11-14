package com.riverlifeline.monitoring.infrastructure.adapter.out.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Keeps what was parsed from a file until the file changes (mtime or size), checking it at most once per {@code
 * checkInterval}. Failures are cached too, so a broken file is not parsed again on every request.
 */
final class FileWatchCache<K, T> {
    private final Clock clock;
    private final Duration checkInterval;
    private final Map<K, Entry<T>> entries = new HashMap<>();

    FileWatchCache(Clock clock, Duration checkInterval) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.checkInterval = Objects.requireNonNull(checkInterval, "checkInterval");
    }

    synchronized T get(K key, Path file, Function<Path, T> loader) {
        Instant now = clock.instant();
        Entry<T> cached = entries.get(key);
        if (cached != null && now.isBefore(cached.checkedAt().plus(checkInterval))) {
            return cached.result();
        }
        Stamp stamp = Stamp.of(file);
        if (cached != null && cached.stamp().equals(stamp)) {
            entries.put(key, cached.checkedAt(now));
            return cached.result();
        }
        Entry<T> loaded;
        try {
            loaded = new Entry<>(stamp, now, loader.apply(file), null);
        } catch (RuntimeException e) {
            loaded = new Entry<>(stamp, now, null, e);
        }
        entries.put(key, loaded);
        return loaded.result();
    }

    /** A file's identity for change detection; both null when the file doesn't exist. */
    private record Stamp(FileTime modified, Long size) {
        static Stamp of(Path file) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                return new Stamp(attributes.lastModifiedTime(), attributes.size());
            } catch (NoSuchFileException e) {
                return new Stamp(null, null);
            } catch (IOException e) {
                // Unknown: force a reload, which reports the real error
                return new Stamp(FileTime.fromMillis(-1), -1L);
            }
        }
    }

    private record Entry<T>(Stamp stamp, Instant checkedAt, T value, RuntimeException failure) {
        Entry<T> checkedAt(Instant instant) {
            return new Entry<>(stamp, instant, value, failure);
        }

        T result() {
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }
}
