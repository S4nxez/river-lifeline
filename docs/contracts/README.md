# Backend ↔ worker contract

The worker turns radar and weather data into river connectivity and drought results. It does that **only** through
this contract: it never reads or writes a file, never keeps a cache or a volume, never parses a product format
(HDF5, GeoTIFF/COG, PNG, GeoJSON, XML) and never calls anything but the backend's internal API. Everything a job
needs comes from the backend in the job itself or as a raw raster the backend serves; everything it produces goes back
to the backend, which stores it.

The machine-readable spec is [`worker-api.yaml`](worker-api.yaml) (OpenAPI 3.1). This page explains it.

## Who does what

| Concern | Owner |
|---|---|
| NISAR pass search and granule choice | **backend**: it creates one `assess_pass` job per new pass |
| NISAR GCOV window read, Earthdata token | **backend**: serves each granule's window as a raw raster |
| Sentinel-1 search and read (STAC, access token, COG) | **backend**: lists the candidate dates and serves the tiles as raw rasters |
| River gauge (ANA telemetry) | **backend**: stores the daily series, puts the days a job needs in it |
| Communities and water occurrence | **backend**: keeps the area files, sends communities in the job and occurrence as a raster |
| Area files (`<id>.json`, `<id>_communities.geojson`, `<id>_occurrence.png`) | **backend**: `server/src/main/resources/areas/` |
| Any cache of remote reads | **backend** if it wants one; the worker has none |
| Fill mask, speckle filter, resampling, water, vegetation, swath edge, routes, low-water rule, Sentinel-1 classes and agreement, status, change, whole-record classes | **worker** |
| Per-pass record, water and Sentinel-1 masks | **backend**: database and its own storage, from the job results |
| Images for the page (radar, water, change) | **backend**: renders them from the result rasters |
| Trained drought models | **backend**: stores the model JSON the training job returns |

## Rules

1. **One way in, one way out.** The worker calls `GET /internal/worker/jobs/next`, downloads the rasters the job
   references (`href`), uploads the rasters of its result, then posts the result or the failure. No other endpoint,
   no other host. `href`s are backend paths; the worker refuses absolute URLs to other hosts (the token goes with
   every request).
2. **Self-contained jobs.** A job carries every value its use case needs (area, method parameters, communities, gauge
   days, earlier results, model). The worker never asks for "the area" or "the weather" on its own and never needs to
   remember a previous job.
3. **No formats, only arrays.** Rasters travel as raw little-endian arrays described by JSON (below). The backend
   decodes products (HDF5, COG, PNG) on the way in and encodes images on the way out.
4. **Stateless worker.** No `DATA_DIR`, no volume, no cache, no model store. Any number of workers can run; a worker
   can be killed at any time and the job handed to another one.
5. **Versioned.** Every job carries `contractVersion` (this page describes **1**). The worker announces the versions
   and job types it runs when it asks for a job; the backend never hands out anything else. A change a reader would
   notice bumps the version on both sides.

## Job lifecycle

```
Worker                                     Backend
  │ GET /jobs/next?types=…&contract=1        │  picks a pending job whose inputs are all ready,
  │ ───────────────────────────────────────▶ │  leases it for leaseSeconds
  │ ◀─────────────── 200 Job (or 204)        │
  │ GET /rasters/{id}  (each href it needs)  │
  │ ───────────────────────────────────────▶ │
  │ POST /jobs/{id}/heartbeat  (long jobs)   │  extends the lease
  │ PUT /jobs/{id}/rasters/{name}            │  one per raster the result declares
  │ POST /jobs/{id}/result  │ /failure       │  stores the result, schedules follow-up jobs
```

- A job is only handed out when every raster it references can be downloaded at once: slow external reads (Earthdata,
  Planetary Computer) happen on the backend before that, never while a worker waits.
- A lease that expires without a result, a heartbeat or a failure puts the job back in the queue. A result or failure
  for a job the worker no longer holds is answered `409` and the worker drops it.
- A failure says whether it is worth retrying (`retryable`): a missing input or a broken raster is not; running out of
  memory may be.

## Rasters

A raster is a JSON description plus a body of raw bytes.

```json
{"href": "/internal/worker/rasters/7f3c…", "width": 5511, "height": 5496, "dtype": "float32", "nodata": "NaN",
 "unit": "linear_power", "crs": "EPSG:32720",
 "origin": {"x": 612345.0, "y": 9654321.0}, "step": {"x": 20.0, "y": -20.0}}
```

- Body: `width × height` values of `dtype` (`uint8`, `int16`, `float32`), little-endian, row-major, row 0 first, no
  header and no padding. Its length is exactly `width × height × size of dtype`. HTTP `Content-Encoding: gzip` is
  allowed both ways and is transparent to the contract.
- Georeferencing: `origin` is the **centre** of pixel (row 0, column 0) in `crs`; the centre of pixel (r, c) is
  `(origin.x + c × step.x, origin.y + r × step.y)`. North-up grids have a negative `step.y`.
- `nodata`: a number, `"NaN"` (float rasters) or null. `unit`: `linear_power`, `db`, `percent` or `class`.
- Class rasters (`unit: "class"`) use these codes, fixed by the contract:

| Raster | Codes |
|---|---|
| `water` | 0 dry, 1 water of a channel at least the safe width wide, 2 narrower channel, 3 water under vegetation (never a route), 255 no data |
| `sentinel1` | 0 undecided, 1 water, 2 land, 255 not seen |
| `change` | 0 unchanged, 1 water lost, 2 water gained, 255 not judged (no data on either pass) |
| `occurrence` (`unit: "percent"`) | 0–100 % of the months with water (JRC 1984–2021), 255 no data |

### The area grid

Every analysis runs on the area's fixed lon/lat grid (`AreaSpec.grid`), the same for every pass. The backend derives
it from the area file: for a `circle`, the bbox is the square around it; `width = round((lon1 − lon0) / step_deg)`, `height = round((lat1 − lat0) / step_deg)`,
`origin = (lon0 + step/2, lat1 − step/2)`, `step = (step_deg, −step_deg)`, `pixelM = step_deg × 111 320 ×
√cos(mid latitude)`. The worker never recomputes it: area-grid rasters it receives and returns use exactly this grid.

## Job types

| `type` | What the worker does | Inputs (all in the job) | Result |
|---|---|---|---|
| `assess_pass` | Reads one NISAR pass onto the area grid (fill mask, dB, speckle filter per granule, resampling), trims the swath edge, maps water and water under vegetation, picks the usable Sentinel-1 dates and classifies them, applies the low-water rule, measures every community's route to the hub | `area`, `pass`, `granules` (HH and, if dual-pol, HV windows, linear power, product CRS), `gauge` days, `occurrence`, `sentinel1Candidates` (VV tiles per date, linear power) | `PassAssessment` + rasters `water`, `radar` (filtered HH, dB, half resolution) and, when a scene was used, `sentinel1` |
| `reevaluate_pass` | Same judgement from the stored masks, without radar: after a change to the method parameters or the communities | `area`, `pass`, stored `water`, stored `sentinel1` and its dates, `gauge`, `occurrence` | `PassAssessment` + raster `water` |
| `detect_change` | Water lost and gained between two shown passes of the same track, where both have data | `grid`, both `water` rasters | `ChangeResult` + raster `change` |
| `classify_communities` | Whole-record classes (`monitored`, `unresolved`, `inland`, `no_data`), per-pass counts over monitored communities and unconfirmed isolations | `accessRadiusM`, community ids, every evaluated pass's routes in time order | `Classification` |
| `train_drought_model` | Trains a region's drought model | `observations`, `droughts` | model as JSON (`DroughtModel`) |
| `forecast_drought` | Forecasts a region's drought risk | `model`, `recent` observations (the model's `lookbackDays` up to `asOf`), `horizonDays` | probability and risk level |

Statuses are names, not codes: `connected` (route at least `safeWidthM` wide), `at_risk` (narrower, or through
unverified low-water ground), `isolated` (no route, or no water within `accessRadiusM`), `no_data` (not covered, or a
route could run through a gap).

### What the backend schedules

- Every few hours: search ASF for passes over each area, choose the best granule of every frame (provisional before
  beta, 20 MHz before 40 MHz, HH dual/single pol first), refresh the gauge, and create an
  `assess_pass` job for each pass it has no result for.
- After an `assess_pass` or `reevaluate_pass` result: `detect_change` against the previous shown pass of the same
  track (and from it to the next one, when a pass arrived out of order), then `classify_communities` for the area.
- After a change to an area file: one `reevaluate_pass` per stored pass, then `classify_communities`.
- Per region, on its own schedule: `train_drought_model`, then `forecast_drought` jobs.

A pass result is applied to the record only when it arrives for the job's current lease, so a pass is never counted
twice.

## Changes from the current contract (`worker/README.md`)

| Before | Now |
|---|---|
| `GET /jobs/next` with `params`; the worker fails job types it doesn't know | `GET /jobs/next?types=…&contract=…`; the job carries all its `inputs` |
| `GET /jobs/{id}/scenes`, scenes downloaded to `DATA_DIR/scenes` as `.tif` files | granule windows and Sentinel-1 tiles referenced in the job, read as raw rasters into memory |
| `GET /area`, `GET /weather`, `GET /droughts` | in the job (`area`, `observations`, `recent`, `droughts`) |
| `assess_connectivity`: per-community Sentinel-1 status comparison | `assess_pass`: Sentinel-1 classes feed the low-water rule; pass-level agreement and mud |
| models pickled to `DATA_DIR/models` | the model is part of the training result and of every forecast job |
| `DATA_DIR` | gone |

Not decided here, to agree with the team: the Flyway migration that stores pass results (`radar_pass`,
`connectivity_assessment` change shape), where the backend keeps result rasters, and how the backend reads NISAR HDF5
windows and Sentinel-1 COG windows (both are byte-range reads of public products).

