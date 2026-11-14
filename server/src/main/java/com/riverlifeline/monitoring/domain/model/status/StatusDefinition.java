package com.riverlifeline.monitoring.domain.model.status;

import java.util.Objects;

/** How a status is shown: colour and shape always together, so the map stays readable without colour. */
public record StatusDefinition(Status status, String label, String color, String shape, int order, String description) {
    public StatusDefinition {
        Objects.requireNonNull(status, "status");
    }
}
