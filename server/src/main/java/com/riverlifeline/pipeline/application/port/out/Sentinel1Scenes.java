package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.domain.model.area.AreaGrid;
import com.riverlifeline.pipeline.domain.model.area.Bbox;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Sentinel-1 RTC VV scenes (Planetary Computer). */
public interface Sentinel1Scenes {
    /** The scenes with a VV band over the box between two dates (both included), in priority order. */
    List<Scene> search(Bbox bbox, LocalDate from, LocalDate to) throws IOException;

    /** The scene's tile cropped to the area, at the coarsest overview no larger than the area's pixel. */
    Optional<RasterRef> read(Scene scene, AreaGrid grid, RasterSink sink) throws IOException;

    record Scene(String id, LocalDate date, String vvHref) {}
}
