package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.job.JobInputs.PassRoutes;
import com.riverlifeline.pipeline.domain.model.job.LeaseId;
import com.riverlifeline.pipeline.domain.model.pass.NisarPass;
import com.riverlifeline.pipeline.domain.model.pass.PassId;
import com.riverlifeline.pipeline.domain.model.pass.PassInfo;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import com.riverlifeline.pipeline.domain.model.result.PassAssessment;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The passes over each area: found by the search, then judged by the worker. */
public interface PassRepository {
    /** Records a pass the search found; a pass not assessed yet gets the newly chosen granules. */
    void saveDiscovered(AreaId area, NisarPass pass, Instant now);

    /** The oldest pass with no assessment and no job, whose staging is due at {@code now}. */
    Optional<StagingCandidate> nextToStage(Instant now);

    /** Staging failed: tried again after {@code retryAfter}, or never when it is null. */
    void stagingFailed(AreaId area, PassId pass, String error, Instant retryAfter);

    Optional<StoredPass> find(AreaId area, PassId pass);

    /** Shown passes of one track with a water raster, oldest first. */
    List<StoredPass> shownOnTrack(AreaId area, int track);

    /** Assessed passes with a water raster judged with another version of the area file. */
    List<StoredPass> stale(AreaId area, String digest);

    /** Every evaluated pass with its community routes, oldest first. */
    List<PassRoutes> evaluatedRoutes(AreaId area);

    Optional<LocalDate> earliestDate(AreaId area);

    /**
     * Stores a pass's assessment in place of the previous one. A raster name missing from {@code rasters} keeps the
     * raster stored before (a re-evaluation brings no radar).
     */
    void saveAssessment(
            AreaId area,
            PassAssessment assessment,
            Map<ResultRasterName, RasterId> rasters,
            String digest,
            LeaseId lease,
            Instant now);

    record StagingCandidate(AreaId area, NisarPass pass, int stagingAttempts) {}

    /** A pass as stored: rasters are null when the pass has none. */
    record StoredPass(
            AreaId area,
            PassInfo info,
            boolean assessed,
            boolean shown,
            boolean evaluated,
            RasterRef water,
            RasterRef sentinel1Classes,
            List<LocalDate> sentinel1Dates,
            String digest) {}
}
