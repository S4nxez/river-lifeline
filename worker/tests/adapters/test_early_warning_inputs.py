import json
from datetime import date
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from worker.adapters.files.early_warning_inputs import grid_of, read_area, read_gauge, read_occurrence, read_passes
from worker.entrypoints import cli

AREAS = Path(__file__).parents[3] / "server" / "src" / "main" / "resources" / "areas"


def test_the_grid_of_tefe_is_the_one_the_backend_hands_the_worker():
    # the water rasters the worker returns for Tefe are 5499 x 5527 (production)
    grid = grid_of(json.loads((AREAS / "tefe.json").read_text(encoding="utf-8")))

    assert (grid.width, grid.height) == (5499, 5527)
    assert grid.bbox == (-65.24494, -3.89742, -64.25506, -2.90258)
    assert grid.origin.x == pytest.approx(-65.24494 + 0.00009)
    assert grid.circle.radius_km == 55


def test_reads_the_area_its_communities_and_occurrence():
    area = read_area(AREAS / "tefe.json")

    assert area.id == "tefe" and len(area.communities) > 100
    assert area.method.safe_width_m == 60 and area.method.widths_m[0] == 40
    occurrence = read_occurrence(AREAS / "tefe_occurrence.png", area.grid)
    assert occurrence.shape == (area.grid.height, area.grid.width) and occurrence.dtype == np.uint8


def test_an_occurrence_off_the_grid_is_refused(tmp_path):
    area = read_area(AREAS / "tefe.json")
    Image.fromarray(np.zeros((3, 3), np.uint8)).save(tmp_path / "occ.png")

    with pytest.raises(ValueError):
        read_occurrence(tmp_path / "occ.png", area.grid)


def test_reads_the_gauge_history(tmp_path):
    (tmp_path / "g.json").write_text(json.dumps({"station_code": "1", "days": [["2026-01-01", 812.5]]}))

    levels, meta = read_gauge(tmp_path / "g.json")

    assert levels == {date(2026, 1, 1): 812.5} and meta["station_code"] == "1"


def test_reads_the_passes_and_their_water_masks(tmp_path):
    area = read_area(AREAS / "tefe.json")
    codes = np.zeros((area.grid.height, area.grid.width), np.uint8)
    codes[0, :3] = [1, 2, 255]
    np.savez_compressed(tmp_path / "w.npz", codes=codes)
    document = {
        "classes": {"a": "monitored"},
        "passes": [
            {"pass_id": "p", "date": "2026-09-10", "statuses": {"a": "isolated"}, "water": "w.npz"},
            {"pass_id": "q", "date": "2026-09-22", "statuses": {"a": "connected"}, "water": None},
        ],
    }
    (tmp_path / "passes.json").write_text(json.dumps(document))

    classes, passes = read_passes(tmp_path / "passes.json", area.grid)

    assert classes == {"a": "monitored"}
    assert passes[0].water[0, :4].tolist() == [True, True, False, False]
    assert passes[0].valid[0, :4].tolist() == [True, True, False, True]
    assert passes[1].water is None and passes[1].day == date(2026, 9, 22)
    assert read_passes(None, area.grid) == ({}, [])


def test_the_cli_runs_the_early_warning_instead_of_the_worker(monkeypatch):
    calls = []
    monkeypatch.setattr(cli, "early_warning", lambda argv: calls.append(argv) or Path("out.json"))
    monkeypatch.setattr(cli, "build_worker", lambda settings: pytest.fail("the worker must not start"))
    monkeypatch.setattr(cli.sys, "argv", ["worker", "early-warning", "--out", "x"])

    cli.main()

    assert calls == [["--out", "x"]]
