package com.riverlifeline.pipeline.application.service;

import com.riverlifeline.pipeline.application.port.out.RasterRepository;
import com.riverlifeline.pipeline.application.port.out.RasterRepository.StoredRaster;
import com.riverlifeline.pipeline.application.port.out.RasterSink;
import com.riverlifeline.pipeline.application.port.out.RasterStore;
import com.riverlifeline.pipeline.application.port.out.RasterStore.StoredBody;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import com.riverlifeline.pipeline.domain.model.raster.RasterRole;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;

/** The sink source adapters write into: the body goes to the store, its description to the repository. */
public final class RasterWriting implements RasterSink {
    private final RasterStore store;
    private final RasterRepository rasters;
    private final Clock clock;
    private final RasterRole role;

    public RasterWriting(RasterStore store, RasterRepository rasters, Clock clock, RasterRole role) {
        this.store = Objects.requireNonNull(store, "store");
        this.rasters = Objects.requireNonNull(rasters, "rasters");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.role = Objects.requireNonNull(role, "role");
    }

    @Override
    public RasterRef write(RasterId id, RasterMeta meta, Body body) throws IOException {
        StoredBody stored;
        try (RasterStore.Writer writer = store.create(id)) {
            body.writeTo(writer.body());
            stored = writer.commit();
        }
        if (stored.byteLength() != meta.byteLength()) {
            store.delete(id);
            throw new IOException(
                    "Raster " + id + " has " + stored.byteLength() + " bytes, its layout says " + meta.byteLength());
        }
        rasters.save(new StoredRaster(id, role, meta, stored, null, null, clock.instant()));
        return new RasterRef(id, meta);
    }
}
