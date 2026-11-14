from typing import Any

from worker.application.ports import AreaProvider, ImageryProvider, RouteMapper
from worker.domain.connectivity import MAX_CROSSCHECK_GAP_DAYS, RouteMeasurement, cross_check
from worker.domain.errors import InvalidJob
from worker.domain.imagery import NISAR, SENTINEL_1, Scene, closest_scene
from worker.domain.jobs import Job, JobResult


class AssessConnectivity:
    """Tells, for a NISAR pass, whether each community still has a water route to the hub and how narrow it is.

    NISAR is the source of every status. A Sentinel-1 scene, when one was taken within a few days of the pass, is
    measured the same way and only cross-checks each status: it never changes it.
    """

    def __init__(
        self, imagery: ImageryProvider, areas: AreaProvider, nisar: RouteMapper, sentinel1: RouteMapper
    ) -> None:
        self._imagery = imagery
        self._areas = areas
        self._nisar = nisar
        self._sentinel1 = sentinel1

    def execute(self, job: Job) -> JobResult:
        scenes = self._imagery.scenes_for(job)
        passes = [scene for scene in scenes if scene.mission == NISAR]
        if not passes:
            raise InvalidJob(f"job {job.id} has no NISAR scene to assess")
        nisar = max(passes, key=lambda scene: scene.acquired_on)
        sentinel1 = closest_scene(
            [scene for scene in scenes if scene.mission == SENTINEL_1], nisar.acquired_on, MAX_CROSSCHECK_GAP_DAYS
        )

        area = self._areas.monitored_area()
        measured = self._nisar.measure(nisar, area.hub, area.communities)
        checks = (
            {m.community_id: m for m in self._sentinel1.measure(sentinel1, area.hub, area.communities)}
            if sentinel1
            else {}
        )

        return JobResult(
            job.id,
            {
                "passId": nisar.id,
                "mission": NISAR,
                "acquiredOn": nisar.acquired_on.isoformat(),
                "assessments": [self._assessment(m, checks.get(m.community_id), sentinel1) for m in measured],
            },
        )

    @staticmethod
    def _assessment(nisar: RouteMeasurement, sentinel1: RouteMeasurement | None, scene: Scene | None) -> dict[str, Any]:
        check = cross_check(nisar, sentinel1, scene.id, scene.acquired_on) if sentinel1 and scene else None
        return {
            "communityId": nisar.community_id,
            "status": nisar.status.value,
            "routeWidthM": round(nisar.width_m) if nisar.width_m is not None and nisar.covered else None,
            "sentinel1": {"sceneOn": check.scene_on.isoformat(), "agrees": check.agrees} if check else None,
        }
