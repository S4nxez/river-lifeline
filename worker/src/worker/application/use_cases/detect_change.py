from worker.application import results
from worker.application.ports import ChangeMapper, RasterSource
from worker.application.use_cases import inputs_of
from worker.domain.errors import InvalidJob, InvalidResult
from worker.domain.jobs import Job, JobResult
from worker.domain.passes import DetectChangeInputs
from worker.domain.rasters import DType, RasterRef, Unit


class DetectChange:
    """Water lost and gained between two shown passes of the same track, where both have data."""

    def __init__(self, mapper: ChangeMapper, rasters: RasterSource) -> None:
        self._mapper = mapper
        self._rasters = rasters

    def execute(self, job: Job) -> JobResult:
        inputs = inputs_of(job, DetectChangeInputs)
        for name, ref in (("previous", inputs.previous), ("current", inputs.current)):
            self._check_water(name, ref, inputs)

        mapped = self._mapper.compare(self._rasters.read(inputs.previous), self._rasters.read(inputs.current))
        if not inputs.grid.holds(mapped.change.meta):
            raise InvalidResult("the change raster must lie on the area grid")

        pixel_km2 = inputs.grid.pixel_km2
        return JobResult(
            job.id,
            {
                "passId": inputs.pass_id,
                "sincePassId": inputs.since_pass_id,
                "lostKm2": mapped.lost_pixels * pixel_km2,
                "gainedKm2": mapped.gained_pixels * pixel_km2,
                "rasters": {"change": results.raster_meta(mapped.change.meta)},
            },
            {"change": mapped.change},
        )

    @staticmethod
    def _check_water(name: str, ref: RasterRef, inputs: DetectChangeInputs) -> None:
        if not inputs.grid.holds(ref.meta) or ref.meta.dtype != DType.UINT8 or ref.meta.unit != Unit.CLASS:
            raise InvalidJob(f"the {name} water raster of job {inputs.pass_id} is not a class raster on the area grid")
