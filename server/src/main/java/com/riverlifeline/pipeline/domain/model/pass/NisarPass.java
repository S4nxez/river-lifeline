package com.riverlifeline.pipeline.domain.model.pass;

import java.util.List;
import java.util.Objects;

/** A pass and the granule chosen for each of its frames, in frame order. */
public record NisarPass(PassInfo info, List<GranuleRef> granules) {
    public NisarPass {
        Objects.requireNonNull(info, "info");
        granules = List.copyOf(granules);
        if (granules.isEmpty()) {
            throw new IllegalArgumentException("A pass has at least one granule");
        }
    }
}
