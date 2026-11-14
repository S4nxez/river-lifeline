from datetime import date

from fakes import scene

from worker.domain.imagery import SENTINEL_1, closest_scene

PASS_DAY = date(2026, 9, 9)


def test_picks_the_scene_closest_to_the_day():
    scenes = [scene("a", "2026-09-05", SENTINEL_1), scene("b", "2026-09-08", SENTINEL_1)]

    assert closest_scene(scenes, PASS_DAY, max_gap_days=4).id == "b"


def test_ignores_scenes_too_far_from_the_day():
    assert closest_scene([scene("old", "2026-09-01", SENTINEL_1)], PASS_DAY, max_gap_days=4) is None
    assert closest_scene([], PASS_DAY, max_gap_days=4) is None


def test_on_a_tie_picks_the_earlier_scene():
    scenes = [scene("after", "2026-09-11", SENTINEL_1), scene("before", "2026-09-07", SENTINEL_1)]

    assert closest_scene(scenes, PASS_DAY, max_gap_days=4).id == "before"
