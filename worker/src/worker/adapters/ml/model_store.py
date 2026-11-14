import pickle
import re
from pathlib import Path

from worker.application.ports import DroughtModel
from worker.domain.errors import ModelNotFound


class PickleModelStore:
    """Keeps one trained model per region as a file in `directory` (a persistent volume in production).

    Only loads files this worker wrote itself: pickle must never read untrusted data.
    """

    def __init__(self, directory: Path) -> None:
        self._directory = directory

    def save(self, region: str, model: DroughtModel) -> None:
        self._directory.mkdir(parents=True, exist_ok=True)
        self._path(region).write_bytes(pickle.dumps(model))

    def load(self, region: str) -> DroughtModel:
        path = self._path(region)
        if not path.exists():
            raise ModelNotFound(f"no drought model trained for region {region!r}")
        return pickle.loads(path.read_bytes())

    def _path(self, region: str) -> Path:
        # Region names come from the backend: keep them from escaping the directory
        return self._directory / f"{re.sub(r'[^A-Za-z0-9_-]', '_', region)}.pkl"
