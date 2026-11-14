import numpy as np

from worker.domain.errors import InvalidJob
from worker.domain.passes import ChangeMap
from worker.domain.rasters import ChangeClass, DType, Raster, RasterMeta, Unit, WaterClass

# Open water, the water routes run on. Water under vegetation is left out: only dual-polarisation passes see it, so
# comparing a single-pol pass with a dual-pol one would report it as change.
OPEN_WATER = (WaterClass.WIDE, WaterClass.NARROW)


class NumpyChangeMapper:
    """Compares two `water` rasters of the same grid pixel by pixel: open water lost, gained, or unchanged, and not
    judged where either pass has no data."""

    def compare(self, previous: Raster, current: Raster) -> ChangeMap:
        if not previous.meta.same_grid(current.meta):
            raise InvalidJob("the two water rasters are not on the same grid")
        before = _classes(previous)
        after = _classes(current)

        was_water = np.isin(before, OPEN_WATER)
        is_water = np.isin(after, OPEN_WATER)
        judged = (before != WaterClass.NO_DATA) & (after != WaterClass.NO_DATA)
        lost = judged & was_water & ~is_water
        gained = judged & ~was_water & is_water

        change = np.full(before.shape, ChangeClass.UNCHANGED, dtype="<u1")
        change[lost] = ChangeClass.LOST
        change[gained] = ChangeClass.GAINED
        change[~judged] = ChangeClass.NOT_JUDGED

        meta = RasterMeta(
            width=current.meta.width,
            height=current.meta.height,
            dtype=DType.UINT8,
            nodata=float(ChangeClass.NOT_JUDGED),
            unit=Unit.CLASS,
            crs=current.meta.crs,
            origin=current.meta.origin,
            step=current.meta.step,
        )
        return ChangeMap(Raster(meta, change.tobytes()), int(lost.sum()), int(gained.sum()))


def _classes(raster: Raster) -> np.ndarray:
    if raster.meta.dtype != DType.UINT8 or raster.meta.unit != Unit.CLASS:
        raise InvalidJob(f"a water raster is uint8 classes, got {raster.meta.dtype} {raster.meta.unit}")
    return np.frombuffer(raster.data, dtype="<u1").reshape(raster.meta.height, raster.meta.width)
