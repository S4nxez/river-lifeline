from datetime import date

from fakes import job, route

from worker.application.use_cases.classify_communities import ClassifyCommunities
from worker.domain.connectivity import ClassifyCommunitiesInputs
from worker.domain.jobs import JobType
from worker.domain.passes import CommunityRoute, EvaluatedPass, RouteStatus


def test_returns_the_classes_totals_and_per_pass_counts_as_the_contract_shapes_them():
    inputs = ClassifyCommunitiesInputs(
        area_id="tefe",
        access_radius_m=500,
        community_ids=("uarini", "far", "new"),
        passes=(
            EvaluatedPass(
                "p1",
                date(2026, 10, 11),
                "10:02",
                (
                    CommunityRoute("uarini", route(RouteStatus.ISOLATED, None, 100)),
                    CommunityRoute("far", route(RouteStatus.ISOLATED, None, 5000)),
                ),
            ),
            EvaluatedPass(
                "p2", date(2026, 10, 23), "10:02", (CommunityRoute("uarini", route(RouteStatus.CONNECTED, 60)),)
            ),
        ),
    )

    result = ClassifyCommunities().execute(job(inputs, JobType.CLASSIFY_COMMUNITIES))

    assert result.rasters == {}
    assert result.data == {
        "areaId": "tefe",
        "classes": {"uarini": "monitored", "far": "inland", "new": "no_data"},
        "totals": {"monitored": 1, "unresolved": 0, "inland": 1, "noData": 1},
        "passes": [
            {
                "passId": "p1",
                "counts": {"connected": 0, "atRisk": 1, "isolated": 0, "noData": 0},
                "unconfirmed": ["uarini"],
            },
            {
                "passId": "p2",
                "counts": {"connected": 1, "atRisk": 0, "isolated": 0, "noData": 0},
                "unconfirmed": [],
            },
        ],
    }
