from worker.domain.connectivity import Community, RouteMeasurement
from worker.domain.imagery import Scene


class ThresholdRouteMapper:
    """Measures water routes on a radar scene by thresholding backscatter: calm water reflects almost nothing.

    One instance per sensor, each with its own calibration: NISAR (L-band, HH) assesses, Sentinel-1 (C-band, VV)
    cross-checks. To implement, per scene:
      1. read the band `polarization` over the monitored area (rasterio/h5py), in dB, and mark the pixels without data;
      2. water = pixels below `water_threshold_db`, cleaned of isolated specks;
      3. for each community: find the water nearest to it, and the largest channel width W for which that water and
         the hub's water stay connected (keep only water at least W/2 from the shore, then label connected pieces);
      4. `covered` is False when the community or its route crosses pixels without data.
    """

    def __init__(self, polarization: str, water_threshold_db: float) -> None:
        self.polarization = polarization
        self.water_threshold_db = water_threshold_db

    def measure(self, scene: Scene, hub: Community, communities: list[Community]) -> list[RouteMeasurement]:
        raise NotImplementedError("ThresholdRouteMapper.measure: not implemented yet")
