package com.riverlifeline.pipeline;

import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.area.AreaGrid;
import com.riverlifeline.pipeline.domain.model.area.AreaId;
import com.riverlifeline.pipeline.domain.model.area.Circle;
import com.riverlifeline.pipeline.domain.model.area.Community;
import com.riverlifeline.pipeline.domain.model.area.GaugeStation;
import com.riverlifeline.pipeline.domain.model.area.Method;
import com.riverlifeline.pipeline.domain.model.area.Target;
import com.riverlifeline.pipeline.domain.model.pass.Direction;
import com.riverlifeline.pipeline.domain.model.pass.GranuleRef;
import com.riverlifeline.pipeline.domain.model.pass.NisarPass;
import com.riverlifeline.pipeline.domain.model.pass.PassId;
import com.riverlifeline.pipeline.domain.model.pass.PassInfo;
import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import com.riverlifeline.pipeline.domain.model.raster.ResultRasterName;
import com.riverlifeline.pipeline.domain.model.result.CommunityRoute;
import com.riverlifeline.pipeline.domain.model.result.PassAssessment;
import com.riverlifeline.pipeline.domain.model.result.RouteResult;
import com.riverlifeline.pipeline.domain.model.result.RouteStatus;
import com.riverlifeline.shared.domain.GeoPoint;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** A small area (a 1 km circle, 100 m pixels) and helpers to build passes and results on it. */
public final class PipelineTestData {
    public static final AreaId AREA_ID = new AreaId("lake");
    public static final AreaGrid GRID = AreaGrid.of(null, new Circle(-64.75, -3.40, 1), 0.0009);
    public static final String STATION = "12900001";

    private PipelineTestData() {}

    public static Method method(Method.Sentinel1Method sentinel1) {
        return new Method(
                1000,
                0.8,
                0.05,
                -40,
                1000,
                -16,
                -14.0,
                null,
                new Method.Speckle("refined_lee", 9.0, 60.0),
                List.of(40.0, 60.0, 100.0),
                60,
                new Method.VegetatedWater(-4, 7, 1000),
                new Method.LowWater(1200, 30, 90, 60, 20),
                sentinel1);
    }

    public static Area area() {
        return area("digest-1");
    }

    public static Area area(String digest) {
        return new Area(
                AREA_ID,
                "Lake",
                GRID,
                new Target("Hub", new GeoPoint(-64.75, -3.40), 2000, null),
                new Target("River", new GeoPoint(-64.74, -3.39), 1500, null),
                List.of(
                        new Community("a", "Alpha", "village", new GeoPoint(-64.751, -3.401), null),
                        new Community(
                                "b", "Beta", "hamlet", new GeoPoint(-64.749, -3.399), new GeoPoint(-64.7491, -3.3991))),
                method(new Method.Sentinel1Method(4, 30, -18, -16.0, -16)),
                new GaugeStation("Gauge", STATION),
                digest);
    }

    public static GranuleRef granule(String date, int track, int frame, String collection) {
        String stamp = date.replace("-", "") + "T100258";
        String name = String.format(
                "NISAR_L2_PR_GCOV_033_%03d_A_%03d_2005_DHDH_A_%s_%s_P05036_N_F_J_001.h5", track, frame, stamp, stamp);
        return GranuleRef.parse(name, "https://products.example/" + name, collection);
    }

    public static NisarPass pass(String date, int track) {
        GranuleRef g = granule(date, track, 176, "NISAR_L2_GCOV_V1");
        return new NisarPass(
                new PassInfo(
                        PassId.of(LocalDate.parse(date), track, Direction.ASCENDING),
                        LocalDate.parse(date),
                        "10:02",
                        track,
                        Direction.ASCENDING),
                List.of(g));
    }

    public static RasterMeta classes() {
        return GRID.meta(DataType.UINT8, NoData.value(255), RasterUnit.CLASS);
    }

    public static RasterMeta radar() {
        return new RasterMeta(
                GRID.width() / 2,
                GRID.height() / 2,
                DataType.FLOAT32,
                NoData.NAN,
                RasterUnit.DB,
                AreaGrid.CRS,
                GRID.origin(),
                GRID.step());
    }

    public static PassAssessment assessment(PassId pass, Map<ResultRasterName, RasterMeta> rasters) {
        return new PassAssessment(
                pass,
                0.95,
                true,
                true,
                12.5,
                1.5,
                null,
                new PassAssessment.Threshold("fixed", -16, -14.0, null, null),
                980.0,
                true,
                new RouteResult(RouteStatus.CONNECTED, 400, 0),
                List.of(
                        new CommunityRoute("a", new RouteResult(RouteStatus.AT_RISK, 40, 10)),
                        new CommunityRoute("b", new RouteResult(RouteStatus.ISOLATED, null, 900))),
                List.of("a"),
                null,
                new PassAssessment.Stats(12.0, 20.0),
                rasters);
    }
}
