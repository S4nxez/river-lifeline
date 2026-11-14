package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.area.AreaId;
import java.util.List;
import java.util.Optional;

/** The study areas, from their files. */
public interface AreaCatalog {
    List<Area> all();

    default Optional<Area> find(AreaId id) {
        return all().stream().filter(a -> a.id().equals(id)).findFirst();
    }
}
