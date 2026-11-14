import pytest
from fakes import AREA, TEFE, FakeAreas, FakeImagery, FakeRouteMapper, scene

from worker.application.use_cases.assess_connectivity import AssessConnectivity
from worker.domain.connectivity import RouteMeasurement
from worker.domain.errors import InvalidJob
from worker.domain.imagery import SENTINEL_1
from worker.domain.jobs import Job, JobType

JOB = Job("42", JobType.ASSESS_CONNECTIVITY)
NISAR_ROUTES = [RouteMeasurement("uarini", True, 40.4), RouteMeasurement("nogueira", True, None)]


def assess(scenes, nisar_routes=None, sentinel1_routes=None):
    nisar = FakeRouteMapper(nisar_routes or {})
    sentinel1 = FakeRouteMapper(sentinel1_routes or {})
    result = AssessConnectivity(FakeImagery(*scenes), FakeAreas(), nisar, sentinel1).execute(JOB)
    return result, nisar, sentinel1


def test_assesses_the_latest_nisar_pass_of_the_job():
    scenes = [scene("n1", "2026-08-28"), scene("n2", "2026-09-09")]

    result, nisar, _ = assess(scenes, {"n2": NISAR_ROUTES})

    [(measured, hub, communities)] = nisar.measured
    assert (measured.id, hub, communities) == ("n2", TEFE, AREA.communities)
    assert (result.data["passId"], result.data["mission"], result.data["acquiredOn"]) == ("n2", "NISAR", "2026-09-09")


def test_reports_each_community_status_and_route_width():
    result, _, _ = assess([scene("n2", "2026-09-09")], {"n2": NISAR_ROUTES})

    assert result.data["assessments"] == [
        {"communityId": "uarini", "status": "at_risk", "routeWidthM": 40, "sentinel1": None},
        {"communityId": "nogueira", "status": "isolated", "routeWidthM": None, "sentinel1": None},
    ]


def test_a_nearby_sentinel1_scene_cross_checks_without_changing_the_status():
    scenes = [
        scene("n2", "2026-09-09"),
        scene("s-far", "2026-08-30", SENTINEL_1),
        scene("s1", "2026-09-08", SENTINEL_1),
    ]
    sentinel1_routes = {"s1": [RouteMeasurement("uarini", True, 25), RouteMeasurement("nogueira", True, 80)]}

    result, _, sentinel1 = assess(scenes, {"n2": NISAR_ROUTES}, sentinel1_routes)

    assert [measured.id for measured, _, _ in sentinel1.measured] == ["s1"]
    uarini, nogueira = result.data["assessments"]
    assert (uarini["status"], uarini["sentinel1"]) == ("at_risk", {"sceneOn": "2026-09-08", "agrees": True})
    assert (nogueira["status"], nogueira["sentinel1"]) == ("isolated", {"sceneOn": "2026-09-08", "agrees": False})


def test_without_a_sentinel1_scene_close_in_time_there_is_no_cross_check():
    scenes = [scene("n2", "2026-09-09"), scene("s-far", "2026-08-30", SENTINEL_1)]

    result, _, sentinel1 = assess(scenes, {"n2": NISAR_ROUTES})

    assert sentinel1.measured == []
    assert all(assessment["sentinel1"] is None for assessment in result.data["assessments"])


def test_a_community_outside_the_nisar_pass_has_no_data_and_no_width():
    result, _, _ = assess([scene("n2", "2026-09-09")], {"n2": [RouteMeasurement("uarini", False, 120)]})

    assert result.data["assessments"] == [
        {"communityId": "uarini", "status": "no_data", "routeWidthM": None, "sentinel1": None}
    ]


def test_a_job_without_a_nisar_scene_is_invalid():
    with pytest.raises(InvalidJob):
        assess([scene("s1", "2026-09-08", SENTINEL_1)])
