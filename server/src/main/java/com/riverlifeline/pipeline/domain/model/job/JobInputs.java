package com.riverlifeline.pipeline.domain.model.job;

import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.area.AreaGrid;
import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.pass.PassId;
import com.riverlifeline.pipeline.domain.model.pass.PassInfo;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import com.riverlifeline.pipeline.domain.model.result.CommunityRoute;
import com.riverlifeline.pipeline.domain.model.source.Drought;
import com.riverlifeline.pipeline.domain.model.source.DroughtModel;
import com.riverlifeline.pipeline.domain.model.source.GaugeDay;
import com.riverlifeline.pipeline.domain.model.source.WeatherDay;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Everything a job carries (contract {@code Job.inputs}), one record per job type. */
public sealed interface JobInputs {
    JobType type();

    /** The rasters the worker downloads for this job. */
    List<RasterRef> rasters();

    /** The gauge days a pass job carries; null when the area has no gauge or no day is known. */
    record Gauge(String station, List<GaugeDay> days) {
        public Gauge {
            Objects.requireNonNull(station, "station");
            days = List.copyOf(days);
        }
    }

    /** The area's window of one granule: HH and, on dual-polarisation frames, HV (null otherwise). */
    record GranuleWindow(String id, int frame, RasterRef hh, RasterRef hv) {
        public GranuleWindow {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(hh, "hh");
        }
    }

    /** The Sentinel-1 VV tiles of one date, in priority order. */
    record Sentinel1Date(LocalDate date, List<RasterRef> tiles) {
        public Sentinel1Date {
            Objects.requireNonNull(date, "date");
            tiles = List.copyOf(tiles);
            if (tiles.isEmpty()) {
                throw new IllegalArgumentException("A Sentinel-1 date has at least one tile");
            }
        }
    }

    /** The Sentinel-1 classes a pass used, as stored from its result. */
    record Sentinel1Classes(List<LocalDate> dates, RasterRef classes) {
        public Sentinel1Classes {
            dates = List.copyOf(dates);
            Objects.requireNonNull(classes, "classes");
        }
    }

    /** One evaluated pass of the whole record. */
    record PassRoutes(PassId passId, LocalDate date, String timeUtc, List<CommunityRoute> communities) {
        public PassRoutes {
            communities = List.copyOf(communities);
        }
    }

    record AssessPass(
            Area area,
            PassInfo pass,
            List<GranuleWindow> granules,
            Gauge gauge,
            RasterRef occurrence,
            List<Sentinel1Date> sentinel1Candidates)
            implements JobInputs {
        public AssessPass {
            Objects.requireNonNull(area, "area");
            Objects.requireNonNull(pass, "pass");
            granules = List.copyOf(granules);
            if (granules.isEmpty()) {
                throw new IllegalArgumentException("assess_pass needs at least one granule");
            }
            sentinel1Candidates = List.copyOf(sentinel1Candidates);
        }

        @Override
        public JobType type() {
            return JobType.ASSESS_PASS;
        }

        @Override
        public List<RasterRef> rasters() {
            List<RasterRef> all = new ArrayList<>();
            for (GranuleWindow g : granules) {
                all.add(g.hh());
                if (g.hv() != null) {
                    all.add(g.hv());
                }
            }
            if (occurrence != null) {
                all.add(occurrence);
            }
            sentinel1Candidates.forEach(d -> all.addAll(d.tiles()));
            return all;
        }
    }

    record ReevaluatePass(
            Area area, PassInfo pass, RasterRef water, Sentinel1Classes sentinel1, Gauge gauge, RasterRef occurrence)
            implements JobInputs {
        public ReevaluatePass {
            Objects.requireNonNull(area, "area");
            Objects.requireNonNull(pass, "pass");
            Objects.requireNonNull(water, "water");
        }

        @Override
        public JobType type() {
            return JobType.REEVALUATE_PASS;
        }

        @Override
        public List<RasterRef> rasters() {
            List<RasterRef> all = new ArrayList<>(List.of(water));
            if (sentinel1 != null) {
                all.add(sentinel1.classes());
            }
            if (occurrence != null) {
                all.add(occurrence);
            }
            return all;
        }
    }

    record DetectChange(
            AreaId areaId, AreaGrid grid, PassId passId, PassId sincePassId, RasterRef previous, RasterRef current)
            implements JobInputs {
        public DetectChange {
            Objects.requireNonNull(areaId, "areaId");
            Objects.requireNonNull(grid, "grid");
            Objects.requireNonNull(passId, "passId");
            Objects.requireNonNull(sincePassId, "sincePassId");
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(current, "current");
        }

        @Override
        public JobType type() {
            return JobType.DETECT_CHANGE;
        }

        @Override
        public List<RasterRef> rasters() {
            return List.of(previous, current);
        }
    }

    record ClassifyCommunities(AreaId areaId, double accessRadiusM, List<String> communityIds, List<PassRoutes> passes)
            implements JobInputs {
        public ClassifyCommunities {
            Objects.requireNonNull(areaId, "areaId");
            communityIds = List.copyOf(communityIds);
            passes = List.copyOf(passes);
        }

        @Override
        public JobType type() {
            return JobType.CLASSIFY_COMMUNITIES;
        }

        @Override
        public List<RasterRef> rasters() {
            return List.of();
        }
    }

    record TrainDroughtModel(String region, List<WeatherDay> observations, List<Drought> droughts)
            implements JobInputs {
        public TrainDroughtModel {
            Objects.requireNonNull(region, "region");
            observations = List.copyOf(observations);
            if (observations.isEmpty()) {
                throw new IllegalArgumentException("Training needs observations");
            }
            droughts = List.copyOf(droughts);
        }

        @Override
        public JobType type() {
            return JobType.TRAIN_DROUGHT_MODEL;
        }

        @Override
        public List<RasterRef> rasters() {
            return List.of();
        }
    }

    record ForecastDrought(String region, int horizonDays, LocalDate asOf, DroughtModel model, List<WeatherDay> recent)
            implements JobInputs {
        public ForecastDrought {
            Objects.requireNonNull(region, "region");
            if (horizonDays < 1) {
                throw new IllegalArgumentException("horizonDays must be positive");
            }
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(model, "model");
            recent = List.copyOf(recent);
        }

        @Override
        public JobType type() {
            return JobType.FORECAST_DROUGHT;
        }

        @Override
        public List<RasterRef> rasters() {
            return List.of();
        }
    }
}
