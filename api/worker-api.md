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

## Backend side

How the backend keeps its half of the contract. None of this is visible to the worker, so none of it changes the
contract version.

### Sizes that drive the choices

Tefé (`circle` of 55 km, `step_deg` 0.00018) gives an area grid of about 5 500 × 5 490 pixels of 20 m:

| Raster | Size, raw | Kept |
|---|---|---|
| NISAR window, one band of one granule, 20 m (20 MHz) | ~120 MB float32 (~480 MB at 10 m, 40 MHz) | until the job ends |
| Sentinel-1 VV tile cropped to the bbox, 20 m overview | ~120 MB float32, up to ~9 dates in ±4 days | until the job ends |
| `water`, `sentinel1`, `change` (area grid, uint8) | ~30 MB each, a few MB gzipped | always |
| `radar` (half resolution, float32) | ~30 MB | until its image is rendered |

An `assess_pass` job stages roughly 0.5–1.5 GB of inputs. Results add a few MB per pass. Production runs on a
Raspberry Pi, so inputs are streamed to disk, never held whole in memory, never put in MySQL, and deleted once used.

### Where rasters are kept

- **Bodies on a volume, metadata in MySQL.** Every raster body (inputs the backend staged and results the worker
  uploaded) is one gzip file on the `rasters` named volume (`RIVER_RASTER_DIR`, `/var/lib/river-lifeline/rasters`),
  at `<first two characters of the id>/<id>.bin.gz`. Its `RasterMeta`, length, SHA-256 and role live in the `raster`
  table. Rendered images (`radar.webp`, `water.png`, `change.png`) will live on the same volume under `images/` when
  rendering lands, together with `/api/v1` reading the worker's results.
- **Streamed both ways.** Bodies are written to a temporary file and renamed when complete. Downloads are sent as
  stored with `Content-Encoding: gzip` when the worker accepts it (decompressed on the fly otherwise); uploads are
  stored gzipped. The backend never holds a whole raster in memory.
- **Behind a port.** Use cases see a `RasterStore` port (application); the file system adapter is the only one for now.
  An S3/MinIO adapter can replace it without touching anything else.
- **Lifecycle.**
  - Input rasters (granule windows, Sentinel-1 tiles) belong to the jobs that reference them (`job_raster`) and are
    deleted when the last of those jobs is done or has failed for good: they can always be read again from the
    source. Occurrence rasters are per area and kept.
  - Uploaded result rasters stay `pending` until the result of that lease is accepted; then they become the pass's.
    The ones of a lease that ended without an accepted result are deleted.
  - `water`, `sentinel1` and `change` are kept (`reevaluate_pass` and `detect_change` read them again). `radar` is
    only rendered: once rendering exists it will be deleted when `radar.webp` does; until then it is kept.
  - A sweep every 15 minutes deletes the bodies nothing references any more (rasters younger than 15 minutes are
    left alone, so a job being queued never loses its inputs).
  - At most `river.pipeline.staged-jobs` (default 2) `assess_pass` jobs have their inputs staged at once, which caps
    the disk the inputs take.
- **Why not the alternatives.** MySQL BLOBs: inputs exceed the default `max_allowed_packet` (64 MB) and every raster
  would go through the InnoDB buffer pool, the binary log and the backups of a Pi. MinIO: one more container on a
  single host for no gain today.

### Migration `V5__worker_jobs_and_pass_results.sql`

The V4 tables were never read by the code and only hold synthetic demo rows, so V5 drops `connectivity_assessment`,
`radar_pass` and `community` (communities come from the area files) and creates:

| Table | Key | What it holds |
|---|---|---|
| `job` | `id` | type, `contract_version`, area (null for drought jobs), subject (`<area>/<pass>`, `<area>/<pass>/<since>`, `<area>`, region…), `inputs` JSON as written for the worker, state (`pending`, `leased`, `done`, `failed`), attempts, the current lease and its expiry, last error, timestamps. A generated column, unique, allows one pending job per type and subject |
| `job_lease` | `id` | One row per hand-out: job, `expires_at`, outcome (`done`, `failed`, `retried`, `expired`). **Its id is the `Job.id` the worker sees**, so a late result of an earlier lease is answered `409` and never applied twice |
| `raster` | `id` | `RasterMeta` (`width`, `height`, `dtype`, `nodata` as text, `unit`, `crs`, origin and step), byte length, SHA-256, role (`input`, `occurrence`, `pending`, `result`), timestamps |
| `job_raster` | job, raster | Which job references which input raster: downloads of rasters no leased job references are `404` |
| `gauge_day` | station, day | Daily median river level (ANA), refreshed by the backend |
| `radar_pass` | area, pass | `PassInfo`, granules chosen (JSON, replaced by better ones until the pass is assessed), staging attempts, error and next try, `coverage`, `shown`, `evaluated`, water/narrow/vegetated km², threshold, `river_level_cm`, `low_water`, hub link (status, bottleneck, distance), stats, the ids of its `water`, `radar`, `sentinel1` rasters, a digest of the method and communities it was judged with (a different digest means `reevaluate_pass`) and the lease that wrote it |
| `pass_sentinel1` | area, pass | `Sentinel1Agreement`, when the pass used scenes |
| `connectivity_assessment` | area, pass, community | status (contract names: `connected`, `at_risk`, `isolated`, `no_data`), `bottleneck_m`, `distance_to_water_m`, `unverified_route`. No foreign key to communities: a community removed from the area file keeps its history |
| `pass_change` | area, pass, since pass | lost and gained km², `change` raster |
| `community_class` | area, community | whole-record class |
| `pass_counts` | area, pass | counts over monitored communities and unconfirmed isolations |
| `drought_model` | region, version | a trained model as the worker returned it (parameters JSON, never executed) |
| `drought_forecast` | region, `as_of`, horizon | probability and risk level |

A result is stored in one transaction with the end of its job and the jobs that follow from it, only when it arrives
for the lease the job is on. A pass's new assessment replaces its rows; a classification replaces the area's.

### Reading NISAR and Sentinel-1 windows

Both are byte-range reads of public cloud products, done in pure Java in the backend's infrastructure layer: no GDAL,
no native HDF5 library, nothing to install on the Pi's ARM image.

- **Shared HTTP range reader.** A `SeekableByteChannel` over `java.net.http` (4 MB block cache, retries). It follows
  the redirect to the signed CDN URL by hand, so the Earthdata token is never sent to the CDN, and signs again when
  that URL expires (401/403).
- **Projection.** `org.locationtech.proj4j` (Apache 2.0, pure Java) turns 50 points per side of the area's bbox into the
  product's UTM projection; the window is the product's pixels that hold them, plus 8 pixels of padding.
- **NISAR GCOV (HDF5).** Granules from ASF search, read with the user token in `EARTHDATA_TOKEN` (a secret, like the
  others). [jHDF][jhdf] (MIT, pure Java) opens the file over the range reader, reads `projection`, `xCoordinates` and
  `yCoordinates` of `/science/LSAR/GCOV/grids/frequencyA`, then `HHHH` and, on dual-pol frames, `HVHV` in strips of the
  dataset's chunk height (512 rows nominally), chunk-aligned as the product specification recommends. Each strip is
  appended to the raster body: float32, linear power, `nodata` `"NaN"`, the product's EPSG, origin at the window's
  first pixel centre. Products are chunked, compressed and use the paged file space strategy (GCOV specification
  JPL D-102274, sec. 3.8); the block cache is sized to whole pages.
- **Sentinel-1 RTC (COG).** STAC search of `sentinel-1-rtc` on Planetary Computer over the bbox, ±`maxDays`, items with
  a `vv` asset, signed with the anonymous SAS token (cached for its lifetime). A small COG reader of our own reads the
  header and IFDs, the GeoKeys (EPSG), tie point and pixel scale (a corner origin moves half a pixel to the centre),
  picks the coarsest overview whose pixel is no larger than the area's, and fetches only the tiles over the window,
  merging adjacent ranges. Decoders: none or Deflate (`java.util.zip`), with no predictor, the horizontal one or the
  floating point one (what Planetary Computer uses); anything else fails staging with a clear error. Values pass
  through untouched (linear power, `nodata` -32768). A scene whose tiles over the window are all empty is left out.
- **Why not the alternatives.** GDAL: native libraries and JNI bindings in the ARM image. GeoTools: tens of MB of
  LGPL dependencies for five TIFF tags. Native HDF5 through JNI: no range reads over HTTPS, so whole 2 GB downloads.
- **Checked with a spike (2026-11-14).** Throwaway readers built as described above, compared with GDAL and h5py.
  - Sentinel-1: item `S1D_IW_GRDH_1SDV_20261025T100327_…_rtc`. The COG is classic TIFF, float32, Deflate with
    predictor 3, 512 × 512 tiles, overviews halving down to 447 px wide, `nodata` -32768, PixelIsArea, EPSG:32720.
    Overview 1 (20.0 m) is the one for Tefé. The Tefé window (5 492 × 4 399 px, clipped by the scene edge) is
    bit-identical to rasterio on all 24 159 308 pixels, with the same window (computed with proj4j and with pyproj) and
    the same origin and step. It took 11 s. The 4 MB blocks downloaded 143 MB of a 1.8 GB file. The real reader fetches
    exact tile ranges instead.
  - NISAR: jHDF 0.14.0 reads the public NISAR sample GCOV `NISAR_L2_PR_GCOV_001_030_A_019_2800_…_D00404` (172 MB, file
    space strategy *page* with 4 MB pages, `HHHH` float32 in 512 × 512 chunks, shuffle + gzip level 1). A 1024 × 1024
    block is bit-identical to h5py. Its coordinates match too. The whole 6 220 × 4 545 grid reads in 25 s with a 1 GB
    heap (55 MB downloaded). Production granules have the same layout. The Tefé window of
    `NISAR_L2_PR_GCOV_033_161_A_176_2005_DHDH_A_20261023T100258_…` (`HHHH` 16 344 × 16 740, EPSG:32720) is 5 492 ×
    5 529 px. It is bit-identical to h5py on all 30 365 268 pixels and took 79 s, downloading 185 MB of a 1.9 GB file.
    The provisional granule of 2026-10-11 on the same track reads the same way.
  - Every source answers for Tefé (2026-11-14). ASF: 56 GCOV granules from 2025-11-02 to 2026-10-30 on tracks 60, 97, 161
    and 169: 49 provisional, 4 beta, 3 `V1`. Sentinel-1 RTC: 61 dates in the same year, at least one within 4 days of
    31 of the 33 NISAR pass dates (not 2026-01-08 and 2026-07-02). ANA station 12900001: levels for every day up to
    today. JRC occurrence tile `70W_0N` and Overpass (238 places in the bbox): reachable. Weather: the Open-Meteo
    archive (ERA5) has precipitation, mean temperature and soil moisture at Tefé up to yesterday. It is a candidate:
    no weather source is chosen yet. Neither is one for the drought history `train_drought_model` needs.
  - Parse the Planetary Computer token response with Jackson: its JSON writes the token's ampersands as Unicode
    escapes, which a plain string search leaves broken.

### Code

The backend's side lives in the bounded context `com.riverlifeline.pipeline` of `server/`, hexagonal like the rest
(`ArchitectureTest` checks both contexts and that neither reaches into the other):

- **Domain**: area and grid, rasters, passes and the granule choice, jobs with their inputs and results, and
  `ResultCheck`, which decides whether a result fits its job (subject, rasters, grid, communities).
- **Application**: `WorkerJobs` (the six calls of the worker API) and `PipelineTasks` (the recurring work), with ports
  for every source and store: `PassSearch`, `GranuleWindows`, `Sentinel1Scenes`, `GaugeSource`, `WeatherSource`,
  `OccurrenceSource`, `RasterStore`, and the repositories. Staging reads every input of a pass first and queues its
  `assess_pass` job only then, so a worker never waits on Earthdata.
- **Infrastructure**: adapters `asf`, `earthdata`, `planetarycomputer`, `ana`, `openmeteo`, `areafiles`,
  `rasterstore`, `jdbc`; the controller under `/internal/worker` with its token filter; the scheduler; the wiring in
  `PipelineConfiguration`. The contract's JSON (camelCase) has its own mapper; the public API keeps snake_case.

### Recurring work

| Task | Every | What |
|---|---|---|
| Pass search | 6 h | ASF search over each area, best granule per frame (final, then provisional, then beta; 20 MHz, then 40 MHz; HH first), new passes recorded; then a `reevaluate_pass` for each pass judged with another version of its area files |
| Gauge | 6 h | ANA telemetry from the last stored day (or 15 days before the first pass), daily medians stored |
| Staging | 5 min | newest passes first, within `staged-jobs`; a failed staging is tried again after 45 min, 1.5 h, 3 h… up to 24 h; a pass no granule reaches is given up |
| Forecasts | 1 day | a `forecast_drought` for every region with a trained model, with Open-Meteo days up to yesterday |
| Leases | 1 min | a lease that ran out puts its job back in the queue, or fails it after `max-attempts` (3) |
| Sweep | 15 min | deletes raster bodies nothing references |

After an `assess_pass` or `reevaluate_pass` result: `detect_change` against the previous shown pass of the same track
and from the pass to the next one, then `classify_communities`. An `assess_pass` that fails for good leaves its pass
aside for 24 h (for ever when the worker says retrying is pointless). Training drought models is not scheduled: no
source of drought history is chosen yet.

### Configuration

| Variable | What |
|---|---|
| `WORKER_TOKEN` | secret of every worker request; without it the worker API answers 401 to everything |
| `EARTHDATA_TOKEN` | NASA Earthdata user token; without it no pass is staged |
| `RIVER_RASTER_DIR` | raster bodies (`/var/lib/river-lifeline/rasters` in the container, on the `rasters` volume) |
| `PIPELINE_ENABLED` | `false` leaves the whole pipeline out |
| `PIPELINE_SCHEDULE` | `false` stops the recurring work; the worker API still answers |

Other knobs are `river.pipeline.*` in `application.properties` (lease, attempts, staged jobs, intervals, addresses
of the sources).

The backend still computes no science: it decodes products and cuts windows; every judgement stays in the worker.

[jhdf]: https://github.com/jamesmudd/jhdf
