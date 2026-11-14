# Worker

Python service that tells, for every NISAR pass, whether each riverside community around Tefé still has a water
route to the town and how narrow it is, cross-checked with Sentinel-1 when a scene is close in time. It also forecasts
droughts from weather history. It never talks to the UI or to external APIs: the backend is the only party it talks to.

```
 UI ──────▶ Backend (Spring) ──────▶ Earthdata, Planetary Computer, ANA...
              ▲          │
  rasters,    │          │  jobs with all their inputs,
  results     │          ▼  raw rasters
             Worker (Python, this service)
```

**The worker pulls, the backend orchestrates.** The worker leases the next job, downloads the rasters it references,
uploads the rasters of its result and posts the result or the failure. It needs no inbound port, keeps no state (no
volume, no cache, no model store) and parses no file format, so any number of workers can run side by side and any of
them can be killed at any time: the backend hands the job out again when its lease expires.

## Layers (clean architecture)

Dependencies point inwards only: `entrypoints → adapters → application → domain`.

| Layer | Folder | What goes there |
|---|---|---|
| Domain | `src/worker/domain/` | Entities and business rules: jobs, rasters and their class codes, the area spec, passes and their judgement, whole-record classes, droughts. Pure Python, no I/O |
| Application | `src/worker/application/` | One use case per job type (`use_cases/`), the ports they need (`ports.py`), the result JSON (`results.py`), the dispatcher, the polling loop and the lease heartbeat |
| Adapters | `src/worker/adapters/` | Port implementations: `backend/` (HTTP client of the backend and the job JSON), `imaging/` (pass analysis and change map with numpy, scipy and pyproj), `ml/` (drought model) |
| Entrypoints | `src/worker/entrypoints/` | `cli.py`: the composition root that wires the adapters into the use cases, and `main()` |

A new algorithm is a new adapter plus one line in `entrypoints/cli.py`. A new job type is a `JobType`, a use case and
one entry in the dispatcher. Use cases are tested with the in-memory fakes in `tests/fakes.py`.

## Backend contract

The worker speaks contract version **1**, [`api/worker-api.md`](../api/worker-api.md) (OpenAPI spec:
[`worker-api.yaml`](../api/worker-api.yaml)):

| Call | What the worker does |
|---|---|
| `GET /internal/worker/jobs/next?types=…&contract=1` | Leases a job of the types it has a use case for. A job of another type or contract version, or with malformed inputs, is handed back as a failure that is not retried |
| `POST /internal/worker/jobs/{id}/heartbeat` | Every third of the lease while the job runs |
| `GET /internal/worker/rasters/{id}` | Reads each raster the job references into memory (gzip accepted); only backend paths, never another host |
| `PUT /internal/worker/jobs/{id}/rasters/{name}` | Uploads each raster of the result, gzipped, before the result |
| `POST /internal/worker/jobs/{id}/result` | `{"data": …}`, checked against the contract before it is sent |
| `POST /internal/worker/jobs/{id}/failure` | `{"reason", "retryable"}`: bad inputs, unknown types and contract breaks are not retryable; running out of memory is |

A `409` on any call means the job is no longer leased to this worker: it is dropped without a word. All calls go to the
backend's internal address (`BACKEND_URL`, `http://backend:8080` in compose), never behind the public proxy, with the
shared secret in the `X-Worker-Token` header.

| Job type | Use case | Algorithm |
|---|---|---|
| `assess_pass`, `reevaluate_pass` | `AssessPass`, `ReevaluatePass` | `RadarPassAnalyzer` (`adapters/imaging/`), see below |
| `detect_change` | `DetectChange` | `NumpyChangeMapper`: open water (classes 1 and 2) lost and gained where both passes have data |
| `classify_communities` | `ClassifyCommunities` | `domain/connectivity.py`: pure Python. Inland: never water within the access radius; unresolved: never linked through a channel at least the narrowest width level; monitored: the rest with data |
| `train_drought_model`, `forecast_drought` | `TrainDroughtModel`, `ForecastDrought` | `BaselineDroughtModel`: **not implemented yet** |

### How a pass is judged (`adapters/imaging/`)

1. **Backscatter** (`backscatter.py`, `refined_lee.py`): per granule, HH (and HV when the area flags water under
   vegetation) from linear power to dB, fill below `validFloorDb` masked, speckle filtered (refined Lee with
   `speckle.looks`, or a box over `speckle.windowM`), resampled to the area grid by nearest pixel. Outside a circular
   area and within `edgeMarginM` of the edge of the data is no data: the signal rolls off there and reads as water.
2. **Water** (`water.py`): below the pass threshold, fixed or measured on the tiles that hold both water and land
   (Kittler-Illingworth, `waterDbAdaptive`), plus darker-than-weak pixels touching it; specks under 1 ha dropped,
   holes under 2 ha filled. Water under vegetation (HH bright, HH - HV large, near open water) is mapped but never a
   route.
3. **Sentinel-1** (`sentinel1.py`): the scene dates within `maxDays` whose river level is within `levelToleranceCm`
   of the pass's; each date's tiles averaged onto the grid and filtered with refined Lee; the darkest of them on each
   pixel, classed as water, land or undecided; agreement with NISAR.
4. **Low water** (`water.py`): at a river level up to `gaugeMaxCm`, on seasonal ground (`seasonalPct` of the water
   occurrence), beds Sentinel-1 sees as mud leave the routes, and broad beds no scene judged make a route at most at
   risk (`unverifiedRoute`).
5. **Routes**: from each community's water access to the hub, by channel width, judged against `safeWidthM` and
   `accessRadiusM`; a route that could run through a gap is no data.

`reevaluate_pass` runs steps 4 and 5 from the stored `water` (open water 1-2, vegetation 3) and `sentinel1` rasters;
it reports the method's fixed thresholds, since the stored water was already cut with the pass's own.

The worker only announces the job types whose algorithm exists (`entrypoints/cli.py`), so the backend keeps the others
pending instead of seeing them fail. Pass the implementation to `build_worker` once it is written.

## Run

```bash
cd worker
python -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
pytest                                   # unit tests
ruff format . && ruff check .            # formatting and lint (the pre-commit hook runs them too)
WORKER_TOKEN=dev BACKEND_URL=http://localhost:8080 python -m worker
```

Settings: `WORKER_TOKEN` (required), `BACKEND_URL`, `POLL_INTERVAL_SECONDS` (5). Nothing is written to disk.

In production the worker is the `worker` service of `podman-compose.yml` (image `...-worker`, built by Release), with the same `WORKER_TOKEN` as the backend. With an empty database the backend's pass search creates an `assess_pass` for every pass of the area, so the first run processes the whole record (backfill) and later runs only the new passes.
