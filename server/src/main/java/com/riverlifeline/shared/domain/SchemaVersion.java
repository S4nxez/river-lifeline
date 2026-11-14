package com.riverlifeline.shared.domain;

import java.util.regex.Pattern;

/** A semver {@code schema_version}: a major change breaks every layer, minor and patch changes don't. */
public record SchemaVersion(int major, int minor, int patch) {
    private static final Pattern SEMVER = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");

    public SchemaVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Negative version number");
        }
    }

    /** Parses {@code "1.0.0"}; anything else is rejected. */
    public static SchemaVersion parse(String value) {
        if (value == null || !SEMVER.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a semver version: " + value);
        }
        String[] parts = value.split("\\.");
        return new SchemaVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
