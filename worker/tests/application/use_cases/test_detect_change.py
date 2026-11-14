import pytest
from fakes import GRID, FakeChangeMapper, FakeRasters, classes, job, meta, ref

from worker.application.use_cases.detect_change import DetectChange
from worker.domain.errors import InvalidJob, InvalidResult
from worker.domain.passes import DetectChangeInputs
from worker.domain.rasters import XY, DType, Raster, RasterMeta, Unit

PREVIOUS = classes(*([1] * 12))
CURRENT = classes(*([0] * 12))
CHANGE = classes(*([1] * 12))


def inputs(previous=None, current=None) -> DetectChangeInputs:
    return DetectChangeInputs(GRID, "p2", "p1", previous or ref("previous"), current or ref("current"))


def rasters() -> FakeRasters:
    return FakeRasters({ref("previous").href: PREVIOUS, ref("current").href: CURRENT})


def test_measures_the_water_lost_and_gained_in_square_kilometres():
    mapper = FakeChangeMapper(CHANGE, lost=12, gained=3)

    result = DetectChange(mapper, rasters()).execute(job(inputs()))

    assert mapper.compared == [(PREVIOUS, CURRENT)]
    assert result.data["passId"] == "p2"
    assert result.data["sincePassId"] == "p1"
    assert (result.data["lostKm2"], result.data["gainedKm2"]) == (12.0, 3.0)
    assert result.data["rasters"]["change"]["dtype"] == "uint8"
    assert result.rasters == {"change": CHANGE}


@pytest.mark.parametrize(
    "bad",
    [
        ref("previous", meta(dtype=DType.FLOAT32, unit=Unit.DB, nodata="NaN")),
        ref("previous", RasterMeta(2, 2, DType.UINT8, 255, Unit.CLASS, GRID.crs, GRID.origin, GRID.step)),
    ],
)
def test_only_compares_class_rasters_on_the_area_grid(bad):
    with pytest.raises(InvalidJob):
        DetectChange(FakeChangeMapper(CHANGE, 0, 0), rasters()).execute(job(inputs(previous=bad)))


def test_a_change_raster_off_the_grid_is_never_posted():
    off_grid = Raster(RasterMeta(4, 3, DType.UINT8, 255, Unit.CLASS, GRID.crs, XY(0, 0), GRID.step), bytes(12))

    with pytest.raises(InvalidResult):
        DetectChange(FakeChangeMapper(off_grid, 0, 0), rasters()).execute(job(inputs()))
