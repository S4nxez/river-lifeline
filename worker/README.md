# Worker

Python service that tells, for every NISAR pass, whether each riverside community around Tefé still has a water
route to the town and how narrow it is, cross-checked with Sentinel-1 when a scene is close in time. It also forecasts
droughts from weather history. It never talks to the UI or to external APIs: the backend is the only party it talks to.

```
 UI ──────▶ Backend (Spring) ──────▶ Earthdata, weather APIs...
              ▲          │
  next job,   │          │  jobs, scene files,
  results     │          ▼  weather and drought history
             Worker (Python, this service)
```

**The worker pulls, the backend orchestrates.** The worker asks the backend for the next job, fetches the inputs the
backend gathered for it, and posts back the result or the failure. It needs no inbound port, any number of workers can
run side by side, and the backend keeps the state of every job (it should hand a job out again if no result arrives in
time).

## Layers (clean architecture)

Dependencies point inwards only: `entrypoints → adapters → application → domain`.

| Layer | Folder | What goes there |
|---|---|---|
| Domain | `src/worker/domain/` | Entities and business rules (jobs, scenes, communities, route measurements and statuses, cross-checks, droughts). Pure Python, no I/O |
| Application | `src/worker/application/` | One use case per job type (`use_cases/`), the ports they need (`ports.py`), the dispatcher and the polling loop |
| Adapters | `src/worker/adapters/` | Port implementations: `backend/` (HTTP client of the backend), `imaging/` (water route mapping), `ml/` (drought model and its storage) |
| Entrypoints | `src/worker/entrypoints/` | `cli.py`: the composition root that wires the adapters into the use cases, and `main()` |

A new algorithm is a new adapter plus one line in `entrypoints/cli.py`. A new job type is a `JobType`, a use case and
one entry in the dispatcher. Use cases are tested with the in-memory fakes in `tests/fakes.py`.

## Backend contract

The contract the worker is moving to is [`api/worker-api.md`](../api/worker-api.md) (OpenAPI spec:
[`worker-api.yaml`](../api/worker-api.yaml)): every job carries all its inputs, rasters travel as raw arrays
the backend serves and receives, and the worker stores nothing (no `DATA_DIR`, no scene files, no model files) and
parses no file format.

The code in `src/` still speaks the previous version, summarised here until it is migrated:

| Endpoint | Answer |
|---|---|
| `GET /internal/worker/jobs/next` | `204`, or `200 {"id", "type", "params"}` |
| `POST /internal/worker/jobs/{id}/result` | body `{"data": {...}}` |
| `POST /internal/worker/jobs/{id}/failure` | body `{"reason": "..."}` |
| `GET /internal/worker/jobs/{id}/scenes` | `[{"id", "mission", "acquiredOn", "polarization", "downloadUrl"}]` |
| `GET /internal/worker/area` | `{"hub": {"id", "name", "lat", "lng"}, "communities": [...]}` |
| `GET /internal/worker/weather?region=&from=&to=` | `[{"day", "precipitationMm", "temperatureC", "soilMoisture"}]` |
| `GET /internal/worker/droughts?region=` | `[{"start", "end", "severity"}]` |

Job types and their `params`: `assess_connectivity` (none: the scenes come from the job), `train_drought_model`
(`region`, `from`, `to`), `forecast_drought` (`region`, `horizonDays`, `asOf`). All endpoints are on the backend's internal address (`BACKEND_URL`, `http://backend:8080` in compose), never behind the
public proxy, and require the shared secret in the `X-Worker-Token` header.

## Run

```bash
cd worker
python -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
pytest                                   # unit tests
ruff format . && ruff check .            # formatting and lint (the pre-commit hook runs them too)
WORKER_TOKEN=dev BACKEND_URL=http://localhost:8080 DATA_DIR=./data python -m worker
```

Settings: `WORKER_TOKEN` (required), `BACKEND_URL`, `POLL_INTERVAL_SECONDS` (5), `DATA_DIR` (`/data`: downloaded
scenes and trained models).

Not implemented yet: the algorithms (`ThresholdRouteMapper`, `BaselineDroughtModel` raise `NotImplementedError`) and
the backend endpoints above. The worker is not in the compose stack until the backend serves them.
