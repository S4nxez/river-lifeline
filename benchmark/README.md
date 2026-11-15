# Exam 3 of the earlier processing code on the worker

`exam_bridge.py` runs exam 3 of the earlier processing repository (`river-monitor benchmark`, develop `5d6b4bc`) with
every pass judged by this repository's worker (`RadarPassAnalyzer`), fed as the backend feeds an `assess_pass` job.
Everything else is the exam's own: pass search, gauge, whole-record classes, change, references and scoring.

Run on the Raspberry Pi on 2026-11-15 (02:41–08:44 UTC, sharing the machine with the production backfill), in a
folder apart from the stack, with `rasterio` 1.5.2 (no 1.4.3 wheel for Python 3.13 on ARM). Files:
`history/20261115T024114Z_v3-new-worker.*` (`.json` result, `.md` report, `.log` run log).

## Scorecard

| Score | v3-sentinel1-mud-veto | v3-darkest-sentinel1 (earlier method, last run) | **v3-new-worker (this repository)** |
|---|---|---|---|
| Synthetic correct share | 0.8 | 0.8 | **0.8** |
| Synthetic dangerous | 5 | 5 | **5** |
| Synthetic conservative | 5 | 5 | **5** |
| Water IoU vs reference | 0.9292 | 0.9292 | **0.9292** |
| Narrow recall | 0.3877 | 0.3877 | **0.3877** |
| False water at shore | 0.0737 | 0.0737 | **0.0737** |
| Status agreement | 0.9611 | 0.9568 | **0.9568** |
| False routes | 0 | 1 | **1** |
| False isolated | 15 | 14 | **14** |
| Gauge rho (lost routes) | -0.759 | -0.757 | **-0.757** |
| Field checks | 1.0 | 1.0 | **1.0** |
| Undecided | 47 | 47 | **47** |
| To review | 1 | 2 | **2** |
| Confirmed false routes | – | 0 | **0** |
| NISAR-S1 agreement | 0.983 | 0.985 | **0.985** |
| NISAR-S1 places | 67 | 62 | **62** |

Every scored value equals the earlier method's last run, `v3-darkest-sentinel1`: the worker reproduces it.

## What differs

Only the NISAR vs Sentinel-1 cross-check, which the exam does not score, differs, and slightly: the agreement of a
pass moves by 0.0001–0.0002, its water areas by up to ~1 km² out of thousands judged, a few disagreement sites move by
about 40 m (two pixels) and a handful of pixels change of probable cause. The most likely reason: the earlier code
caches every filtered Sentinel-1 mosaic in float16 and reads it back, the worker keeps it in float32, so a few pixels
right at the Sentinel-1 water and land thresholds fall on the other side.
