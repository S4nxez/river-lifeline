from worker.application.ports import RasterSource
from worker.domain.passes import AssessPassInputs, PassAssessment, ReevaluatePassInputs


class RadarPassAnalyzer:
    """Judges a NISAR pass on the area grid with the parameters of `area.method` (api/worker-api.md, job types).

    To implement, for assess_pass:
      1. per granule, read HH (and HV) from `rasters`, mask fill below `validFloorDb`, convert to dB, filter speckle
         (`speckle`), and resample onto the area grid (`area.grid`, EPSG:4326) from the product's CRS;
      2. trim the swath edge (`edgeMarginM`) and measure the coverage (`minCoverage`, `minCoverageShown`);
      3. map water with `waterDb` / `waterDbWeak` (or `waterDbAdaptive`) and water under vegetation (`vegetatedWater`);
      4. pick the Sentinel-1 dates the gauge allows (`sentinel1.levelToleranceCm`), classify them and measure the
         agreement; apply the low-water rule (`lowWater`, occurrence, gauge);
      5. measure the hub <-> river link and every community's route (`widthsM`, `safeWidthM`, `accessRadiusM`);
      6. return the `water`, `radar` and (when used) `sentinel1` rasters with the codes of the contract.
    reevaluate_pass runs steps 4 and 5 from the stored `water` and `sentinel1` rasters.
    """

    def assess(self, inputs: AssessPassInputs, rasters: RasterSource) -> PassAssessment:
        raise NotImplementedError("RadarPassAnalyzer.assess: not implemented yet")

    def reevaluate(self, inputs: ReevaluatePassInputs, rasters: RasterSource) -> PassAssessment:
        raise NotImplementedError("RadarPassAnalyzer.reevaluate: not implemented yet")
