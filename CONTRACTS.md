# Contracts

The contract between the backend and the worker is [`api/worker-api.md`](api/worker-api.md)
([`api/worker-api.yaml`](api/worker-api.yaml)). This file keeps the other documents the layers exchange.

## Pass list: `layer_ids`

Every item of `GET /api/v1/aois/{aoi}/passes` (and the pass detail, which repeats the item) lists the ids of the layers
the pass has, e.g. `["radar_hh", "sentinel1_vv", "water", "change", "nodata"]`, the same ids as the detail's
`layers[].id`. The page's clock offers only the evaluated passes that have both `radar_hh` and `sentinel1_vv`.

## Early warning (experimental): `aois/<id>/early_warning.json`

Written offline by the worker's CLI (`python -m worker early-warning`, [`docs/early-warning.md`](docs/early-warning.md)),
served as published by `GET /api/v1/aois/{aoi}/early-warning`: from the data folder in the files mode
(`RIVER_SOURCE=files`), from `RIVER_EARLY_WARNING_DIR` in the pipeline mode; `404` when the area has none. It is a separate, experimental product:
nothing in it changes a status, a count or a colour of the main record.

`schema_version` `1.0.0`. A reader refuses another major version. Required: `schema_version`, `aoi_id` (the area's id),
`experimental` (always `true`), `method`, `issued_at`, `communities`, `hindcast`.

| Field | Meaning |
|---|---|
| `generated_at` | When the file was written (UTC) |
| `method.summary`, `method.limitations[]` | The method in plain words, and its limits, shown with it |
| `method.level_step_cm`, `method.levels_swept_cm` | The 10 cm grid of gauge levels swept, `[lowest, highest]` |
| `method.occurrence_no_data_share` | Share of the area without water occurrence (treated as never water) |
| `gauge.station_code`, `gauge.datum_note` | The gauge, and how its record was put on the current datum |
| `issued_at` | Date of the forecast: only data available on that day was used |
| `level_forecast[]` | `{date, p10_cm, p50_cm, p90_cm}` for each day after `issued_at` (120 days) |
| `observed_level[]` | `{date, level_cm}` around `issued_at` (120 days either side) |
| `communities[]` | One per community of the area file, sorted by `expected_cutoff_date` (none last) |
| `communities[].class` | Whole-record class (`monitored`, `unresolved`, `inland`, `no_data`); only `monitored` ones get thresholds and warnings |
| `communities[].at_risk_below_cm`, `isolated_below_cm` | Gauge level at or below which the river twin sees the community at risk (route under the safe width) or cut off; null when it never is within the levels swept |
| `communities[].bottleneck` | `{location: [lon, lat], width_class_m}`: approximate point where its safe-width water ends closest to the hub, and the width level of its route, at the at-risk level; null without one |
| `communities[].warnings[]` | `{horizon_days (7, 14, 30), p_at_risk, p_isolated}`: chance the level is below each threshold at that horizon |
| `communities[].expected_cutoff_date` | First day the median forecast falls below `isolated_below_cm`; null if it never does |
| `hindcast.issues[]` | Past issue dates (each forecast used only the data of its day) |
| `hindcast.communities[]` | `{community_id, observed_isolated_on, first_warned_on, lead_days}` for every monitored community the radar saw isolated after the first issue |
| `hindcast.misses[]`, `false_alarms[]` | Seen isolated but never warned in time; warned, judged by the radar, never seen isolated |
| `hindcast.summary` | `{median_lead_days, warned, observed, false_alarms, passes}` |
| `hindcast.predicted_cutoffs` | Each issue's expected cut-off date by community |
| `validation.mask_iou` | Mean IoU of the synthetic water against the radar's, `{area, permanent_water, seasonal_ground}`; null without masks |
| `validation.status_confusion` | `{observed_vs_synthetic: {observed: {synthetic: count}}, agreement}` over monitored communities on every evaluated pass |
| `validation.passes[]` | `{pass_id, date, level_cm, mask_iou?}` |
