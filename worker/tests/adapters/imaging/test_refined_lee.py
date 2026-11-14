import numpy as np
import pytest

from worker.adapters.imaging import refined_lee as module
from worker.adapters.imaging.backscatter import box_filter
from worker.adapters.imaging.refined_lee import refined_lee


def gamma_db(mean_db: float, shape: tuple[int, int], looks: int, seed: int = 0) -> np.ndarray:
    """Backscatter with multiplicative speckle of `looks` looks (gamma, mean 1), in dB."""
    rng = np.random.default_rng(seed)
    return (10 * np.log10(rng.gamma(looks, 1 / looks, shape) * 10 ** (mean_db / 10))).astype(np.float32)


def shore() -> np.ndarray:
    """Lake (-23 dB) on the west half, forest (-7 dB) on the east half."""
    db = np.full((40, 40), -7.0, np.float32)
    db[:, :20] = -23.0
    return db


def test_homogeneous_areas_are_smoothed_without_bias():
    db = gamma_db(-10.0, (120, 120), 9)

    out = refined_lee(db, 9.0)

    lin_in, lin_out = 10 ** (db / 10), 10 ** (out / 10)
    looks_in = (lin_in.mean() / lin_in.std()) ** 2
    inner = lin_out[10:-10, 10:-10]
    assert (inner.mean() / inner.std()) ** 2 > 4 * looks_in
    assert float(lin_out.mean() / lin_in.mean()) == pytest.approx(1.0, abs=0.03)


def test_a_noiseless_shore_is_kept_and_a_narrow_line_is_less_blurred_than_by_the_box():
    np.testing.assert_allclose(refined_lee(shore(), 9.0), shore(), atol=1e-3)  # an edge is not blurred at all

    line = np.full((40, 40), -7.0, np.float32)
    line[:, 19:21] = -23.0  # 40 m channel: thinner than the half windows
    lee, box = refined_lee(line, 9.0)[5:-5, 19:21], box_filter(line, 3)[5:-5, 19:21]
    assert (lee < box - 3).all()  # much darker than the box leaves it, not exact


def test_a_60m_channel_keeps_its_width_where_the_box_filter_shrinks_it():
    mean = np.full((200, 200), -7.0, np.float32)
    mean[:, 99:102] = -23.0  # 3 px = 60 m
    noisy = mean + gamma_db(0.0, mean.shape, 9, seed=3)

    lee = refined_lee(noisy, 9.0)[5:-5, 99:102] < -15
    box = box_filter(noisy, 3)[5:-5, 99:102] < -15

    assert lee.mean() > 0.95
    assert box.mean() < 0.5


def test_a_shore_next_to_a_gap_is_kept():
    db = shore()
    db[:6, :] = np.nan  # swath edge just north of the shore's end
    db[:, 36:] = np.nan

    out = refined_lee(db, 9.0)

    ok = np.isfinite(db)
    np.testing.assert_allclose(out[ok], db[ok], atol=1e-3)


def test_no_data_stays_no_data_and_does_not_spread():
    db = gamma_db(-10.0, (60, 60), 9)
    db[20:30, 20:30] = np.nan

    np.testing.assert_array_equal(np.isnan(refined_lee(db, 9.0)), np.isnan(db))


def test_chunks_give_the_same_result(monkeypatch):
    db = gamma_db(-12.0, (90, 50), 9, seed=5)
    db[:, 20:23] = -23.0
    whole = refined_lee(db, 9.0)

    monkeypatch.setattr(module, "CHUNK_ROWS", 7)

    np.testing.assert_allclose(refined_lee(db, 9.0), whole, atol=1e-5)


def test_looks_must_be_positive():
    with pytest.raises(ValueError):
        refined_lee(shore(), 0)
