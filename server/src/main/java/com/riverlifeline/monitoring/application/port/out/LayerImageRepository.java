package com.riverlifeline.monitoring.application.port.out;

import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.pass.LayerImage;
import java.util.Optional;

/** The styled layer PNGs of the passes; empty when the file isn't there. */
public interface LayerImageRepository {
    Optional<BinaryContent> open(AoiId aoiId, LayerImage image);
}
