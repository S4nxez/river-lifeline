package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.application.port.out.RasterStore.StoredBody;
import com.riverlifeline.pipeline.domain.model.job.LeaseId;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterRole;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** What the backend knows about each stored raster body. */
public interface RasterRepository {
    void save(StoredRaster raster);

    Optional<StoredRaster> find(RasterId id);

    /** Whether a job held by a worker at {@code now} references the raster. */
    boolean referencedByLeasedJob(RasterId id, Instant now);

    /** The body uploaded under this name for this lease, if any. */
    Optional<StoredRaster> pending(LeaseId lease, ResultRasterName name);

    List<StoredRaster> pendingOf(LeaseId lease);

    /** Makes an uploaded body part of a result, with the layout the result declared. */
    void promote(RasterId id, RasterMeta meta);

    void delete(RasterId id);

    /** Rasters created before {@code createdBefore} that nothing needs any more (see {@link RasterRole}). */
    List<RasterId> unreferenced(Instant createdBefore);

    /**
     * A stored raster. {@code meta} is null for an upload whose result has not arrived; {@code lease} and {@code name}
     * are set for uploads only.
     */
    record StoredRaster(
            RasterId id,
            RasterRole role,
            RasterMeta meta,
            StoredBody body,
            LeaseId lease,
            ResultRasterName name,
            Instant createdAt) {}
}
