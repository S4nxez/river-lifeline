import numpy as np
import pytest

from worker.adapters.imaging import refined_lee as module
from worker.adapters.imaging.backscatter import box_filter
from worker.adapters.imaging.refined_lee import refined_lee


def speckled(db: np.ndarray, looks: int, seed: int = 7) -> np.ndarray:
    """Multiplicative speckle of `looks` looks (gamma, mean 1) on a backscatter field in dB."""
    noise = np.random.default_rng(seed).gamma(looks, 1.0 / looks, db.shape)
    return (10 * np.log10(np.power(10.0, db / 10.0) * noise)).astype(np.float32)


def bank(rows: int = 40, cols: int = 40) -> np.ndarray:
    """Water (-20 dB) on the west half, land (-5 dB) on the east half."""
    db = np.full((rows, cols), -5.0, np.float32)
    db[:, : cols // 2] = -20.0
    return db


@pytest.mark.parametrize("db", [bank(), bank().T, bank(40, 3000)])
def test_keeps_a_clean_edge_exactly_where_a_box_filter_blurs_it(db):
    db = db.astype(np.float32)

    np.testing.assert_allclose(refined_lee(db, 9), db, atol=1e-4)
    assert np.abs(box_filter(db, 7) - db).max() > 1


def test_follows_a_diagonal_bank():
    rows, cols = np.mgrid[:40, :40]
    db = np.where(cols > rows, -5.0, -20.0).astype(np.float32)

    filtered = refined_lee(db, 9)

    inner = (slice(4, -4), slice(4, -4))
    assert np.abs(filtered - db)[inner].mean() < np.abs(box_filter(db, 7) - db)[inner].mean() / 4


def test_smooths_speckle_in_homogeneous_areas():
    noisy = speckled(np.full((60, 60), -10.0, np.float32), looks=4)

    filtered = refined_lee(noisy, 4)

    inner = (slice(5, -5), slice(5, -5))
    assert filtered[inner].std() < noisy[inner].std() / 2
    assert abs(float(np.median(filtered[inner])) + 10) < 1


def test_does_not_average_water_with_land_next_to_a_noisy_bank():
    noisy = speckled(bank(), looks=4)

    filtered = refined_lee(noisy, 4)

    assert np.median(filtered[5:-5, 18:20]) < -15  # the last water columns stay water
    assert np.median(filtered[5:-5, 20:22]) > -10  # the first land columns stay land


def test_more_looks_mean_less_speckle_to_remove():
    noisy = speckled(np.full((40, 40), -10.0, np.float32), looks=4)

    # assuming a cleaner product, the filter trusts the pixels more and smooths less
    assert refined_lee(noisy, 1000).std() > refined_lee(noisy, 1).std()


def test_no_data_takes_no_part_and_stays_no_data():
    db = bank()
    db[10:15, 10:15] = np.nan

    filtered = refined_lee(db, 9)

    assert np.isnan(filtered[10:15, 10:15]).all()
    np.testing.assert_allclose(filtered[16:, :], db[16:, :], atol=1e-4)


def test_strips_give_the_same_result_as_one_pass(monkeypatch):
    noisy = speckled(np.random.default_rng(3).uniform(-20, -5, (50, 30)).astype(np.float32), looks=4)
    whole = refined_lee(noisy, 4)

    monkeypatch.setattr(module, "ROWS_PER_STRIP", 7)

    np.testing.assert_allclose(refined_lee(noisy, 4), whole, atol=1e-5)


def test_looks_must_be_positive():
    with pytest.raises(ValueError):
        refined_lee(bank(), 0)
