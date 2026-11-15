# Early warning (experimental)

**Question.** Had this existed during the 2026 dry season, how many days in advance would it have warned the people
running the drought response in Tefé which communities were going to lose their water route?

It is a proof of concept, kept apart from the main record: it never changes a status, a count or a colour of the main
view, and the page shows it in its own collapsed section, marked "Experimental · hindcast".

## Method

1. **River twin.** For every pixel, the JRC Global Surface Water occurrence (Pekel et al., 2016) says in what share of
   the observations of 1984–2021 it was water. Over the same years the Tefé gauge (ANA 12900001) gives the distribution
   of the river level. A pixel with occurrence `o` is taken to dry out below the level that was exceeded `o` % of the
   time: `drying_level = quantile(levels, 1 - o/100)`. The water at any gauge level `L` is then the pixels with
   `drying_level < L`.
2. **Thresholds per community.** At every level from the record's low to the issue day's level, in 10 cm steps, the
   same route analysis the worker runs on a radar pass (connectivity to the hub and narrowest width) is run on the
   synthetic water. A community's `at_risk_below_cm` is the highest level at which its route is no longer safe, and
   `isolated_below_cm` the highest at which it has no route at all. Only the 101 distinct drying levels change the water,
   so each distinct mask is analysed once.
3. **Level forecast.** The gauge anomaly against the day-of-year climatology persists: the forecast for day `t + h` is
   the climatology of that day plus the anomaly of day `t`. Its 10/50/90 % range comes from the errors of the same
   forecast issued every 5 days over the whole record, using only data before each issue date. This is a baseline, not
   a hydrological model: an ensemble forecast (Pappenberger et al., 2015; Chevuturi et al., 2022, 2023) would replace it.
4. **Warnings.** For each monitored community and horizon (7, 14, 30 days), the chance the level falls below each
   threshold, from the forecast quantiles. `expected_cutoff_date` is the first day the median falls below
   `isolated_below_cm`.
5. **Hindcast.** The forecast is issued on 1 August, 15 August and 1 September 2026, each time with only the data of
   that day. A community is warned when a forecast before the radar saw it isolated expected its cut-off; the lead time
   is the days between the first such forecast and the radar pass. A false alarm is a community expected to be cut off
   that the radar judged and never saw isolated.
6. **Validation.** For every evaluated NISAR pass, the synthetic water at the pass day's level is compared with the
   radar water (IoU over the area, over permanent water and over seasonal ground), and the synthetic status of each
   monitored community with the radar's.

Only communities the whole record classes as `monitored` get thresholds; `unresolved`, `inland` and `no_data` ones are
listed with their class only.

## Data

| Input | Source |
|---|---|
| Water occurrence | JRC Global Surface Water v1.4, occurrence 1984–2021 (Pekel et al., 2016), the area's `<id>_occurrence.png` |
| Gauge record | ANA HidroWeb, station 12900001 (Tefé), daily levels, plus the project's own telemetry since 2025 (`fixtures/data/aois/tefe/gauge_history.json`, built by `scripts/fetch_gauge_history.py`) |
| Radar truth | The evaluated NISAR passes of the main pipeline (water masks and community statuses) |

The gauge changed datum in 2024: the levels up to 2024-08-13 are moved to the current datum (+497 cm), and the days
from 2024-08-14 to 2024-12-31, whose datum is inconsistent, are left out. The current datum matches the telemetry
(1071.5 cm on 2025-10-20 in both).

## Running it

```sh
cd worker
python -m worker early-warning \
  --area-file ../server/src/main/resources/areas/tefe.json \
  --occurrence ../server/src/main/resources/areas/tefe_occurrence.png \
  --gauge ../fixtures/data/aois/tefe/gauge_history.json \
  --passes passes.json \
  --issued-at 2026-09-01 \
  --out ../fixtures/data/aois/tefe/early_warning.json
```

`passes.json` lists the evaluated passes (`classes` of the record, and per pass its `statuses` and a `water` mask file,
`.npy` or `.npz` with the 0/1/2/255 codes). It takes about half an hour on a laptop for Tefé. The output format is in
[CONTRACTS.md](../CONTRACTS.md); the backend serves it at `GET /api/v1/aois/{aoi}/early-warning`.

**Publishing it in production** (`RIVER_SOURCE=pipeline`): copy the file to `<folder>/aois/<id>/early_warning.json` on
the server, outside the checkout, set `RIVER_EARLY_WARNING_HOST_DIR=<folder>` in the `.env` and recreate the backend
(`podman-compose up -d backend`). The backend checks the file at most once a minute, so a new version needs no restart.
Without the variable the backend mounts the empty `early-warning/` folder of the repository and answers 404.

The fixture `fixtures/data/aois/tefe/early_warning.json` was generated by this command with **hand-made passes**
(`fixtures/data/aois/tefe/early_warning_passes.json`): its hindcast numbers exercise the page, they are not results.
The results on the real passes are in the pull request that introduced this section.

## Limits

- Width is not depth: a wide channel can be too shallow for a boat, and the occurrence map cannot tell.
- The river twin assumes the water at a given gauge level is the same every year; sandbanks and channels move.
- The forecast persists today's anomaly: it cannot see a rain event coming, and its range comes from past errors.
- One gauge stands for the whole area; lakes and side channels can lag behind or lead the main river.
- The hindcast covers one dry season and a few radar passes: its numbers are an illustration, not a skill score.

## References

- Pekel, J.-F., Cottam, A., Gorelick, N., Belward, A. S. (2016). High-resolution mapping of global surface water and
  its long-term changes. *Nature* 540, 418–422.
- Pappenberger, F., Ramos, M. H., Cloke, H. L., et al. (2015). How do I know if my forecasts are better? Using
  benchmarks in hydrological ensemble prediction. *Journal of Hydrology* 522, 697–713.
- Chevuturi, A., Klingaman, N. P., Woolnough, S. J., et al. (2022, 2023). Forecasting annual maximum and minimum water
  levels of the Amazon river with statistical and seasonal forecast models.
- Santos de Lima, L., et al. (2024). Severe droughts reduce river navigability and isolate communities and
  businesses in the Brazilian Amazon. *Communications Earth & Environment* 5.
- Bangor University river navigability dataset for the Amazon, Zenodo, doi:10.5281/zenodo.19099190.
- Instituto de Desenvolvimento Sustentável Mamirauá: community knowledge of the Tefé lake and Middle Solimões.
