package com.riverlifeline.pipeline.application.service;

import com.riverlifeline.pipeline.application.port.out.OccurrenceSource;
import com.riverlifeline.pipeline.application.port.out.RasterRepository;
import com.riverlifeline.pipeline.application.port.out.RasterRepository.StoredRaster;
import com.riverlifeline.pipeline.application.port.out.RasterSink;
import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** An area's occurrence raster, written once per version of the area files and shared by every job. */
public final class OccurrenceService {
    private final OccurrenceSource source;
    private final RasterRepository rasters;
    private final RasterSink sink;

    public OccurrenceService(OccurrenceSource source, RasterRepository rasters, RasterSink occurrenceSink) {
        this.source = Objects.requireNonNull(source, "source");
        this.rasters = Objects.requireNonNull(rasters, "rasters");
        this.sink = Objects.requireNonNull(occurrenceSink, "occurrenceSink");
    }

    public synchronized Optional<RasterRef> occurrence(Area area) throws IOException {
        RasterId id = idOf(area);
        Optional<StoredRaster> stored = rasters.find(id);
        if (stored.isPresent() && stored.get().meta() != null) {
            return Optional.of(new RasterRef(id, stored.get().meta()));
        }
        return source.write(area, id, sink);
    }

    static RasterId idOf(Area area) {
        return new RasterId("occ-" + area.id() + "-"
                + area.digest().substring(0, Math.min(16, area.digest().length())));
    }
}
