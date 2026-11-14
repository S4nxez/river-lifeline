package com.riverlifeline.pipeline.domain.model.result;

import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.pass.PassId;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Result of {@code classify_communities}: whole-record classes and per-pass counts over monitored communities. */
public record Classification(AreaId areaId, Map<String, CommunityClass> classes, List<PassCounts> passes)
        implements JobResult {
    public Classification {
        Objects.requireNonNull(areaId, "areaId");
        classes = Map.copyOf(classes);
        passes = List.copyOf(passes);
    }

    @Override
    public Map<ResultRasterName, RasterMeta> rasters() {
        return Map.of();
    }

    /** Counts of one pass; an unconfirmed isolation counts as at risk. */
    public record PassCounts(
            PassId passId, int connected, int atRisk, int isolated, int noData, List<String> unconfirmed) {
        public PassCounts {
            Objects.requireNonNull(passId, "passId");
            if (connected < 0 || atRisk < 0 || isolated < 0 || noData < 0) {
                throw new IllegalArgumentException("Counts must not be negative");
            }
            unconfirmed = List.copyOf(unconfirmed);
        }
    }
}
