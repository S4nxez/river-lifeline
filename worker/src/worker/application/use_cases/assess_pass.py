from worker.application import results
from worker.application.ports import PassAnalyzer, RasterSource
from worker.application.use_cases import inputs_of
from worker.domain.area import AreaSpec
from worker.domain.errors import InvalidResult
from worker.domain.jobs import Job, JobResult
from worker.domain.passes import AssessPassInputs, PassAssessment, PassInfo, ReevaluatePassInputs
from worker.domain.rasters import DType, Unit

# Result rasters each job type may return; `water` is always required. A reevaluation returns only `water`: the
# Sentinel-1 classes it judged with are the ones the backend already keeps from the pass's assessment.
ASSESS_RASTERS = {"water", "radar", "sentinel1", "sentinel1_vv"}
REEVALUATE_RASTERS = {"water"}


class AssessPass:
    """Judges one NISAR pass over an area: water, routes from every community to the hub, Sentinel-1 agreement."""

    def __init__(self, analyzer: PassAnalyzer, rasters: RasterSource) -> None:
        self._analyzer = analyzer
        self._rasters = rasters

    def execute(self, job: Job) -> JobResult:
        inputs = inputs_of(job, AssessPassInputs)
        assessment = self._analyzer.assess(inputs, self._rasters)
        return _checked_result(job, inputs.area, inputs.pass_info, assessment, ASSESS_RASTERS)


class ReevaluatePass:
    """Judges a pass again from its stored masks, after a change to the method parameters or the communities."""

    def __init__(self, analyzer: PassAnalyzer, rasters: RasterSource) -> None:
        self._analyzer = analyzer
        self._rasters = rasters

    def execute(self, job: Job) -> JobResult:
        inputs = inputs_of(job, ReevaluatePassInputs)
        assessment = self._analyzer.reevaluate(inputs, self._rasters)
        return _checked_result(job, inputs.area, inputs.pass_info, assessment, REEVALUATE_RASTERS)


def _checked_result(
    job: Job, area: AreaSpec, pass_info: PassInfo, assessment: PassAssessment, allowed: set[str]
) -> JobResult:
    """The result, once it keeps the promises the contract makes to the backend."""
    if assessment.pass_id != pass_info.id:
        raise InvalidResult(f"assessed pass {assessment.pass_id!r}, the job is about {pass_info.id!r}")
    names = set(assessment.rasters)
    if "water" not in names or not names <= allowed:
        raise InvalidResult(f"{job.type} returns rasters {sorted(names)}; allowed {sorted(allowed)}, water required")
    for name in names & {"water", "sentinel1"}:
        meta = assessment.rasters[name].meta
        if not area.grid.holds(meta) or meta.dtype != DType.UINT8 or meta.unit != Unit.CLASS:
            raise InvalidResult(f"raster {name!r} must be a uint8 class raster on the area grid")
    if "sentinel1" in allowed and (assessment.sentinel1 is None) != ("sentinel1" not in names):
        raise InvalidResult("the sentinel1 raster comes with the Sentinel-1 agreement, and only with it")
    if "sentinel1_vv" in names and "sentinel1" not in names:
        raise InvalidResult("the sentinel1_vv image comes only with the Sentinel-1 classes")
    if assessment.evaluated:
        expected = sorted(c.id for c in area.communities)
        if sorted(c.community_id for c in assessment.communities) != expected:
            raise InvalidResult("an evaluated pass has one route per community of the area")
    return JobResult(job.id, results.pass_assessment(assessment), dict(assessment.rasters))
