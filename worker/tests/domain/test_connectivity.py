from datetime import date

from fakes import route

from worker.domain.connectivity import CommunityClass, StatusCounts, classify
from worker.domain.passes import CommunityRoute, EvaluatedPass, RouteStatus

CONNECTED = route(RouteStatus.CONNECTED, 100)
AT_RISK = route(RouteStatus.AT_RISK, 40)
ISOLATED = route(RouteStatus.ISOLATED, None, 200)
FAR = route(RouteStatus.ISOLATED, None, 2000)
NO_DATA = route(RouteStatus.NO_DATA, None, None)
RADIUS = 500


def evaluated(pass_id: str, day: str, **routes) -> EvaluatedPass:
    return EvaluatedPass(
        pass_id, date.fromisoformat(day), "10:00", tuple(CommunityRoute(cid, r) for cid, r in routes.items())
    )


def classes_of(*passes: EvaluatedPass, ids=("a",)) -> dict[str, CommunityClass]:
    return classify("tefe", RADIUS, list(ids), list(passes)).classes


def test_a_community_found_a_route_on_any_pass_is_monitored():
    assert classes_of(evaluated("p1", "2026-01-01", a=ISOLATED), evaluated("p2", "2026-02-01", a=AT_RISK)) == {
        "a": CommunityClass.MONITORED
    }


def test_a_community_always_far_from_water_is_inland():
    assert classes_of(evaluated("p1", "2026-01-01", a=FAR), evaluated("p2", "2026-02-01", a=NO_DATA)) == {
        "a": CommunityClass.INLAND
    }


def test_a_community_near_water_that_never_had_a_route_is_unresolved():
    assert classes_of(evaluated("p1", "2026-01-01", a=FAR), evaluated("p2", "2026-02-01", a=ISOLATED)) == {
        "a": CommunityClass.UNRESOLVED
    }


def test_a_community_linked_only_through_channels_narrower_than_the_narrowest_width_is_unresolved():
    narrow_only = route(RouteStatus.AT_RISK, 0, 0)

    assert classes_of(evaluated("p1", "2026-01-01", a=narrow_only)) == {"a": CommunityClass.UNRESOLVED}


def test_a_community_never_near_water_is_inland_even_with_a_link_to_the_river_far_away():
    linked_far = route(RouteStatus.ISOLATED, 100, 2000)

    assert classes_of(evaluated("p1", "2026-01-01", a=linked_far)) == {"a": CommunityClass.INLAND}


def test_a_community_without_data_on_any_pass_is_no_data_even_when_missing_from_the_passes():
    assert classes_of(evaluated("p1", "2026-01-01", a=NO_DATA), ids=("a", "added_later")) == {
        "a": CommunityClass.NO_DATA,
        "added_later": CommunityClass.NO_DATA,
    }


def test_counts_cover_only_monitored_communities():
    result = classify(
        "tefe",
        RADIUS,
        ["a", "b", "inland"],
        [evaluated("p1", "2026-01-01", a=CONNECTED, b=AT_RISK, inland=FAR)],
    )

    assert result.passes[0].counts == StatusCounts(connected=1, at_risk=1)
    assert result.totals == {
        CommunityClass.MONITORED: 2,
        CommunityClass.UNRESOLVED: 0,
        CommunityClass.INLAND: 1,
        CommunityClass.NO_DATA: 0,
    }


def test_a_first_isolation_is_unconfirmed_and_counts_as_at_risk_until_the_next_pass_with_data_confirms_it():
    result = classify(
        "tefe",
        RADIUS,
        ["a"],
        [
            evaluated("p1", "2026-01-01", a=CONNECTED),
            evaluated("p2", "2026-02-01", a=ISOLATED),
            evaluated("p3", "2026-03-01", a=NO_DATA),
            evaluated("p4", "2026-04-01", a=ISOLATED),
        ],
    )

    assert [(p.pass_id, p.counts, p.unconfirmed) for p in result.passes] == [
        ("p1", StatusCounts(connected=1), ()),
        ("p2", StatusCounts(at_risk=1), ("a",)),
        ("p3", StatusCounts(no_data=1), ()),
        ("p4", StatusCounts(isolated=1), ()),
    ]


def test_an_isolation_on_the_first_pass_with_data_is_unconfirmed():
    result = classify(
        "tefe", RADIUS, ["a"], [evaluated("p1", "2026-01-01", a=ISOLATED), evaluated("p2", "2026-02-01", a=AT_RISK)]
    )

    assert result.passes[0].unconfirmed == ("a",)


def test_passes_are_judged_oldest_first_whatever_order_they_come_in():
    result = classify(
        "tefe",
        RADIUS,
        ["a"],
        [evaluated("late", "2026-02-01", a=ISOLATED), evaluated("early", "2026-01-01", a=CONNECTED)],
    )

    assert [p.pass_id for p in result.passes] == ["early", "late"]
    assert result.passes[1].unconfirmed == ("a",)
