import threading

from fakes import FakeQueue

from worker.application.heartbeat import Heartbeat
from worker.domain.errors import BackendUnavailable


def wait_until(condition) -> None:
    for _ in range(500):
        if condition():
            return
        threading.Event().wait(0.01)
    raise AssertionError("condition never met")


def test_beats_until_the_job_ends():
    queue = FakeQueue()

    with Heartbeat(queue, "7", lease_seconds=1, fraction=0.01) as heartbeat:
        wait_until(lambda: len(queue.heartbeats) >= 2)
    beats = len(queue.heartbeats)
    threading.Event().wait(0.05)

    assert set(queue.heartbeats) == {"7"}
    assert len(queue.heartbeats) == beats
    assert heartbeat.lost is False


def test_a_lost_lease_stops_the_beats_and_is_reported():
    queue = FakeQueue()
    queue.lease_lost = True

    with Heartbeat(queue, "7", lease_seconds=1, fraction=0.01) as heartbeat:
        wait_until(lambda: heartbeat.lost)

    assert queue.heartbeats == ["7"]


def test_keeps_beating_through_a_backend_outage():
    class Flaky(FakeQueue):
        def heartbeat(self, job_id):
            super().heartbeat(job_id)
            if len(self.heartbeats) == 1:
                raise BackendUnavailable("connection refused")
            return 1

    queue = Flaky()

    with Heartbeat(queue, "7", lease_seconds=1, fraction=0.01) as heartbeat:
        wait_until(lambda: len(queue.heartbeats) >= 2)

    assert heartbeat.lost is False
