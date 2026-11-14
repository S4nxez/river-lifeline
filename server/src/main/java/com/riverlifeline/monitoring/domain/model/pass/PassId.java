package com.riverlifeline.monitoring.domain.model.pass;

import com.riverlifeline.monitoring.domain.model.Identifier;

/** {@code nisar-<acq UTC compact>-t<track>-f<frame>}, e.g. {@code nisar-20261023t094512z-t081-f012}. */
public record PassId(String value) {
    public PassId {
        Identifier.require(value, "pass");
        if (PassSelector.LATEST_ALIAS.equals(value)) {
            throw new IllegalArgumentException("'latest' is an alias, not a pass id");
        }
    }

    public static PassId of(String value) {
        return new PassId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
