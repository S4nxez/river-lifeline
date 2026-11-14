package com.riverlifeline.pipeline.domain.model.area;

import java.util.List;
import java.util.Objects;

/**
 * A study area as its files describe it. {@code gauge} is null when the area has none; {@code digest} changes whenever
 * the area file or its communities change, which calls for re-evaluating the stored passes.
 */
public record Area(
        AreaId id,
        String name,
        AreaGrid grid,
        Target hub,
        Target river,
        List<Community> communities,
        Method method,
        GaugeStation gauge,
        String digest) {

    public Area {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(hub, "hub");
        Objects.requireNonNull(river, "river");
        communities = List.copyOf(communities);
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(digest, "digest");
    }

    public List<String> communityIds() {
        return communities.stream().map(Community::id).toList();
    }
}
