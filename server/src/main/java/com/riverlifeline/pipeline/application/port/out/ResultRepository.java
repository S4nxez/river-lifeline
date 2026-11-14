package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.result.ChangeResult;
import com.riverlifeline.pipeline.domain.model.result.Classification;
import com.riverlifeline.pipeline.domain.model.result.DroughtForecast;
import com.riverlifeline.pipeline.domain.model.result.TrainedDroughtModel;
import com.riverlifeline.pipeline.domain.model.source.DroughtModel;
import java.time.Instant;
import java.util.Optional;

/** The results that are not a pass assessment. */
public interface ResultRepository {
    /** Stores the change of a pass; it replaces the pass's change since any other pass. */
    void saveChange(AreaId area, ChangeResult change, RasterId changeRaster, Instant now);

    /** Replaces the area's whole-record classes and per-pass counts. */
    void saveClassification(Classification classification, Instant now);

    void saveDroughtModel(TrainedDroughtModel trained, Instant now);

    Optional<DroughtModel> latestDroughtModel(String region);

    void saveForecast(DroughtForecast forecast, Instant now);
}
