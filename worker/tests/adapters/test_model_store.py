from dataclasses import dataclass

import pytest

from worker.adapters.ml.model_store import PickleModelStore
from worker.domain.errors import ModelNotFound


@dataclass
class TrainedModel:
    weights: list[float]

    def train(self, observations, droughts) -> None: ...

    def predict(self, recent, horizon_days) -> float:
        return 0.0


def test_a_saved_model_loads_back(tmp_path):
    store = PickleModelStore(tmp_path / "models")

    store.save("murcia", TrainedModel([0.1, 0.2]))

    assert store.load("murcia") == TrainedModel([0.1, 0.2])


def test_each_region_has_its_own_model(tmp_path):
    store = PickleModelStore(tmp_path)
    store.save("murcia", TrainedModel([1.0]))
    store.save("almeria", TrainedModel([2.0]))

    assert store.load("murcia").weights == [1.0]


def test_a_region_without_a_model_is_not_found(tmp_path):
    with pytest.raises(ModelNotFound):
        PickleModelStore(tmp_path).load("murcia")


def test_region_names_cannot_escape_the_directory(tmp_path):
    store = PickleModelStore(tmp_path / "models")

    store.save("../../etc/x", TrainedModel([]))

    assert [path.parent for path in (tmp_path / "models").iterdir()] == [tmp_path / "models"]
