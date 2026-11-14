# River Lifeline — frontend

Vanilla JavaScript page (ES modules, no build step) that shows which riverside communities around Tefé can still
reach the town by water, according to the latest NISAR passes, with the Sentinel-1 cross-check of each result.

It only talks to the backend, with relative URLs (the app is served under a path prefix):

| Request                                             | Used for                                        |
| --------------------------------------------------- | ----------------------------------------------- |
| `GET ./api/v1/aois/{aoi}`                           | The area and its reference port (the hub)       |
| `GET ./api/v1/aois/{aoi}/summary`                   | The latest evaluated pass and its river level   |
| `GET ./api/v1/aois/{aoi}/passes/latest/communities` | Every monitored community's status on that pass |
| `GET ./api/v1/aois/{aoi}/communities/{id}`          | The route width of one community on every pass  |

The area comes from the page URL (`?aoi=...`, default `tefe`). `src/services/api.js` maps the responses (contract 2
of CONTRACTS.md) into the page's own view model. When the API can't be reached, or has no communities yet, the page
shows the demo data of `src/data/demo.js` and says DEMO DATA in the header.

## Structure

- `index.html`: page shell.
- `src/app.js`: entry point; loads the overview, keeps the selection, wires the components.
- `src/status.js`: what each status means (labels, colours, severity order) and the connected threshold.
- `src/format.js`: dates, widths and river levels for display.
- `src/services/api.js`: the backend calls and the demo fallback. `src/services/mapService.js`: Leaflet map.
- `src/components/`: community list, route width chart, status panel.
- `tests/`: `node:test` + jsdom, with Leaflet, Chart.js and `fetch` faked.

## Run

Locally, `docker compose -f compose.dev.yml up -d` at the repository root serves this folder on
http://localhost:5500 with `/api` proxied to the backend (see `docs/devops.md`). Tests: `npm test`.

The map tiles (CARTO), Leaflet and Chart.js load from CDNs.

The CARTO basemaps API key is a constant in `src/services/mapService.js`: it is public by design (every tile URL
carries it), so it is protected on CARTO's side instead, restricted to our domains, to basemaps and to a quota.
Without a key CARTO watermarks the tiles with "API key required".
