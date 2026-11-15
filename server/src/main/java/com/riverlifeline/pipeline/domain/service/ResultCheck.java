package com.riverlifeline.pipeline.domain.service;

import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.area.AreaGrid;
import com.riverlifeline.pipeline.domain.model.job.Job;
import com.riverlifeline.pipeline.domain.model.job.JobSubject;
import com.riverlifeline.pipeline.domain.model.job.JobType;
import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import com.riverlifeline.pipeline.domain.model.result.ChangeResult;
import com.riverlifeline.pipeline.domain.model.result.Classification;
import com.riverlifeline.pipeline.domain.model.result.CommunityRoute;
import com.riverlifeline.pipeline.domain.model.result.DroughtForecast;
import com.riverlifeline.pipeline.domain.model.result.JobResult;
import com.riverlifeline.pipeline.domain.model.result.PassAssessment;
import com.riverlifeline.pipeline.domain.model.result.TrainedDroughtModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether a result fits its job: the right kind for the job's type, about the job's subject, with exactly the rasters
 * that type may declare and each area-grid raster on the area's grid. Every problem found is listed (an empty list
 * means the result can be stored).
 */
public final class ResultCheck {
    private ResultCheck() {}

    /** {@code area} is the job's area, or null for drought jobs. */
    public static List<String> problems(Job job, JobResult result, Area area) {
        List<String> problems = new ArrayList<>();
        switch (job.type()) {
            case ASSESS_PASS, REEVALUATE_PASS -> {
                if (!(result instanceof PassAssessment pass)) {
                    problems.add(job.type().wire() + " needs a PassAssessment");
                    break;
                }
                if (area == null || !job.subject().equals(JobSubject.pass(area.id(), pass.passId()))) {
                    problems.add("passId " + pass.passId() + " is not the job's pass");
                }
                Set<ResultRasterName> allowed = job.type() == JobType.ASSESS_PASS
                        ? Set.of(
                                ResultRasterName.WATER,
                                ResultRasterName.RADAR,
                                ResultRasterName.SENTINEL1,
                                ResultRasterName.SENTINEL1_VV)
                        : Set.of(ResultRasterName.WATER);
                rasters(pass.rasters(), Set.of(ResultRasterName.WATER), allowed, area, problems);
                if (area != null) {
                    communities(pass, area, problems);
                }
                if (!pass.evaluated()
                        && (pass.hubLink() != null || !pass.communities().isEmpty())) {
                    problems.add("a pass that is not evaluated has no hub link and no community routes");
                }
            }
            case DETECT_CHANGE -> {
                if (!(result instanceof ChangeResult change)) {
                    problems.add("detect_change needs a ChangeResult");
                    break;
                }
                if (area == null
                        || !job.subject().equals(JobSubject.change(area.id(), change.passId(), change.sincePassId()))) {
                    problems.add("passId/sincePassId are not the job's passes");
                }
                Set<ResultRasterName> only = Set.of(ResultRasterName.CHANGE);
                rasters(change.rasters(), only, only, area, problems);
            }
            case CLASSIFY_COMMUNITIES -> {
                if (!(result instanceof Classification classification)) {
                    problems.add("classify_communities needs a Classification");
                    break;
                }
                if (area == null || !classification.areaId().equals(area.id())) {
                    problems.add("areaId is not the job's area");
                }
            }
            case TRAIN_DROUGHT_MODEL -> {
                if (!(result instanceof TrainedDroughtModel trained)) {
                    problems.add("train_drought_model needs a TrainedDroughtModel");
                } else if (!job.subject().equals(JobSubject.region(trained.region()))) {
                    problems.add("region is not the job's region");
                }
            }
            case FORECAST_DROUGHT -> {
                if (!(result instanceof DroughtForecast forecast)) {
                    problems.add("forecast_drought needs a DroughtForecast");
                } else if (!job.subject()
                        .equals(JobSubject.forecast(forecast.region(), forecast.asOf(), forecast.horizonDays()))) {
                    problems.add("region, asOf or horizonDays are not the job's");
                }
            }
        }
        return problems;
    }

    private static void rasters(
            Map<ResultRasterName, RasterMeta> declared,
            Set<ResultRasterName> required,
            Set<ResultRasterName> allowed,
            Area area,
            List<String> problems) {
        for (ResultRasterName name : required) {
            if (!declared.containsKey(name)) {
                problems.add("raster " + name.wire() + " is missing");
            }
        }
        declared.forEach((name, meta) -> {
            if (!allowed.contains(name)) {
                problems.add("raster " + name.wire() + " is not expected from this job");
            } else if (area != null) {
                String issue = gridIssue(name, meta, area.grid());
                if (issue != null) {
                    problems.add("raster " + name.wire() + ": " + issue);
                }
            }
        });
    }

    /** Null when the raster has the layout the contract fixes for its name. */
    static String gridIssue(ResultRasterName name, RasterMeta meta, AreaGrid grid) {
        // the images for the page: half the area grid, in dB
        if (name == ResultRasterName.RADAR || name == ResultRasterName.SENTINEL1_VV) {
            if (meta.dtype() != DataType.FLOAT32 || meta.unit() != RasterUnit.DB) {
                return "must be float32 dB";
            }
            if (meta.width() != grid.width() / 2 || meta.height() != grid.height() / 2) {
                return "must be half the area grid (" + grid.width() / 2 + " x " + grid.height() / 2 + ")";
            }
            return AreaGrid.CRS.equals(meta.crs()) ? null : "must be in " + AreaGrid.CRS;
        }
        if (meta.dtype() != DataType.UINT8 || meta.unit() != RasterUnit.CLASS) {
            return "must be uint8 classes";
        }
        if (!meta.sameGridAs(grid.meta(DataType.UINT8, NoData.value(255), RasterUnit.CLASS))) {
            return "must be on the area grid";
        }
        return null;
    }

    private static void communities(PassAssessment pass, Area area, List<String> problems) {
        Set<String> known = new HashSet<>(area.communityIds());
        Set<String> seen = new HashSet<>();
        for (CommunityRoute route : pass.communities()) {
            if (!known.contains(route.communityId())) {
                problems.add("community " + route.communityId() + " is not in the area");
            } else if (!seen.add(route.communityId())) {
                problems.add("community " + route.communityId() + " appears twice");
            }
        }
    }
}
