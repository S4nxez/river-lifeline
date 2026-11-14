package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import java.io.IOException;
import java.util.Optional;

/** An area's water occurrence (uint8 percent on the area grid, 255 no data), from its area files. */
public interface OccurrenceSource {
    /** Writes it under {@code id}; empty when the area has none. */
    Optional<RasterRef> write(Area area, RasterId id, RasterSink sink) throws IOException;
}
