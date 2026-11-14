import pytest

from worker.config import Settings


def test_defaults_to_the_backend_on_the_internal_network():
    settings = Settings.from_env({"WORKER_TOKEN": "s3cret"})

    assert settings.backend_url == "http://backend:8080"
    assert settings.poll_interval_seconds == 5


def test_reads_every_setting_from_the_environment():
    env = {"WORKER_TOKEN": "t", "BACKEND_URL": "http://localhost:8080", "POLL_INTERVAL_SECONDS": "0.5"}

    settings = Settings.from_env(env)

    assert (settings.backend_url, settings.worker_token, settings.poll_interval_seconds) == (
        "http://localhost:8080",
        "t",
        0.5,
    )


def test_needs_no_data_directory():
    assert not hasattr(Settings.from_env({"WORKER_TOKEN": "t"}), "data_dir")


def test_the_token_is_required():
    with pytest.raises(ValueError, match="WORKER_TOKEN"):
        Settings.from_env({})


def test_the_poll_interval_must_be_positive():
    with pytest.raises(ValueError, match="POLL_INTERVAL_SECONDS"):
        Settings.from_env({"WORKER_TOKEN": "t", "POLL_INTERVAL_SECONDS": "0"})
