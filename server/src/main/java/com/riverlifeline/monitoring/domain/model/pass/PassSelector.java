package com.riverlifeline.monitoring.domain.model.pass;

import com.riverlifeline.monitoring.domain.model.Identifier;
import java.util.Objects;
import java.util.Optional;

/** A {@code pass_id} as a client sends it: a pass id or the alias {@code latest} (the latest evaluated pass). */
public record PassSelector(String raw) {
    public static final String LATEST_ALIAS = "latest";
    public static final PassSelector LATEST = new PassSelector(LATEST_ALIAS);

    public PassSelector {
        Objects.requireNonNull(raw, "raw");
    }

    /** Never fails: a malformed id just selects no pass. */
    public static PassSelector parse(String raw) {
        return raw == null || raw.isBlank() || LATEST_ALIAS.equals(raw) ? LATEST : new PassSelector(raw);
    }

    public static PassSelector of(PassId passId) {
        return new PassSelector(passId.value());
    }

    public boolean isLatest() {
        return LATEST_ALIAS.equals(raw);
    }

    /** The pass id it names, if it names one with a valid id. */
    public Optional<PassId> passId() {
        return isLatest() || !Identifier.isValid(raw) ? Optional.empty() : Optional.of(new PassId(raw));
    }

    @Override
    public String toString() {
        return raw;
    }
}
