from worker.domain.imagery import ChangeReport, Scene


class LogRatioChangeDetector:
    """SAR change detection by log-ratio of backscatter, the usual baseline for radar imagery.

    To implement: read both rasters (rasterio), co-register them, compute 10 * log10(after / before) per pixel,
    count the pixels beyond `threshold_db` as changed, and report their share and the mean change.
    """

    def __init__(self, threshold_db: float = 3.0) -> None:
        self.threshold_db = threshold_db

    def detect(self, before: Scene, after: Scene) -> ChangeReport:
        raise NotImplementedError("LogRatioChangeDetector.detect: not implemented yet")
