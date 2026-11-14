from collections.abc import Mapping
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    """The worker's configuration, read from environment variables. The worker is stateless: no data directory."""

    backend_url: str
    worker_token: str
    poll_interval_seconds: float

    @classmethod
    def from_env(cls, env: Mapping[str, str]) -> "Settings":
        token = env.get("WORKER_TOKEN", "")
        if not token:
            raise ValueError("WORKER_TOKEN is required: the shared secret the backend expects from the worker")
        poll_interval = float(env.get("POLL_INTERVAL_SECONDS", "5"))
        if poll_interval <= 0:
            raise ValueError(f"POLL_INTERVAL_SECONDS must be positive, got {poll_interval}")
        return cls(
            # The backend's address on the internal network, not the public URL
            backend_url=env.get("BACKEND_URL", "http://backend:8080"),
            worker_token=token,
            poll_interval_seconds=poll_interval,
        )
