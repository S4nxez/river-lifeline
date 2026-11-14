from pathlib import Path

import pytest

from worker.config import Settings


def test_defaults_to_the_backend_on_the_internal_network():
    settings = Settings.from_env({"WORKER_TOKEN": "s3cret"})

    assert settings.backend_url == "http://backend:8080"
    assert settings.poll_interval_seconds == 5
    assert settings.scenes_dir == Path("/data/scenes")
    assert settings.models_dir == Path("/data/models")


def test_reads_every_setting_from_the_environment():
    env = {
        "WORKER_TOKEN": "t",
        "BACKEND_URL": "http://localhost:8080",
        "POLL_INTERVAL_SECONDS": "0.5",
        "DATA_DIR": "/w",
    }

    settings = Settings.from_env(env)

    assert (settings.backend_url, settings.poll_interval_seconds, settings.data_dir) == (
        "http://localhost:8080",
        0.5,
        Path("/w"),
    )


def test_the_token_is_required():
    with pytest.raises(ValueError, match="WORKER_TOKEN"):
        Settings.from_env({})


def test_the_poll_interval_must_be_positive():
    with pytest.raises(ValueError, match="POLL_INTERVAL_SECONDS"):
        Settings.from_env({"WORKER_TOKEN": "t", "POLL_INTERVAL_SECONDS": "0"})
