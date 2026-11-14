# River Lifeline — frontend

Vanilla JavaScript page (ES modules, no build step) that shows which riverside communities around Tefé can still
reach the town by water, according to the latest NISAR passes, with the Sentinel-1 cross-check of each result.

It only talks to the backend, with relative URLs (the app is served under a path prefix):

| Request                                             | Used for                                                          |
| --------------------------------------------------- | ----------------------------------------------------------------- |
| `GET ./api/v1/meta`                                 | The layer catalog: each layer's name, default visibility, opacity |
| `GET ./api/v1/aois/{aoi}`                           | The area: name, bounds, connected width and its port (the hub)    |
| `GET ./api/v1/aois/{aoi}/summary`                   | The latest evaluated pass and its river level                     |
| `GET ./api/v1/aois/{aoi}/passes`                    | Every pass, for the pass selector                                 |
| `GET ./api/v1/aois/{aoi}/passes/{pass}`             | A pass's images (`layers`: url and bounds of each PNG)            |
| `GET ./api/v1/aois/{aoi}/passes/{pass}/communities` | Every monitored community's status on that pass (`latest` first)  |
| `GET ./api/v1/aois/{aoi}/passes/{pass}/layers/*`    | The images themselves, through the `url` of each layer            |
| `GET ./api/v1/aois/{aoi}/communities/{id}`          | The route width of one community on every pass                    |

The area comes from the page URL (`?aoi=...`, default `tefe`). `src/services/api.js` maps the responses (contract 2
of CONTRACTS.md) into the page's own view model. When the API can't be reached, or has no communities yet, the page
shows the demo data of `src/data/demo.js` and says DEMO DATA in the header. Nothing about the area is written in the
page: its name, bounds and connected width all come from the API.

## The map

The page turns around the map. On it: the communities (most in need first), the layers panel and a legend that also
filters by status. The layers panel picks the pass, the basemap (map or satellite photo), the radar image and its
opacity, and the analysis overlays the pass serves (water, change...), named and defaulted from `/meta`.

- **Radar:** one image at a time, Off, NISAR or Sentinel-1, to compare by switching between them. NISAR is the
  contract's `radar_hh` layer; the Sentinel-1 image is expected as a layer `sentinel1_vv` (`src/imagery.js`), and its
  button stays disabled while no pass serves it.
- **Images the API can't serve** are flagged in the panel; the rest of the map carries on. A layer's `url` is read
  relative to the page (`/api/v1/...` becomes `./api/v1/...`), since the app lives under a path prefix.
- **Basemaps** are the only map content not from the backend: CARTO (map) and EOX's Sentinel-2 cloudless mosaic
  (satellite, free with attribution for non-commercial use). The monitor's own images always come from the API.

## Structure

- `index.html`: page shell.
- `src/app.js`: entry point; loads the overview, keeps the selection, wires the components.
- `src/status.js`: what each status means (labels, colours, severity order).
- `src/format.js`: dates, widths and river levels for display.
- `src/theme.js`: the light/dark switch.
- `src/imagery.js`: which images the map draws (radar sources, overlays, the user's choices).
- `src/services/api.js`: the backend calls and the demo fallback. `src/services/mapService.js`: Leaflet map.
- `src/components/`: community list, layers panel, status filter, route width chart, status panel.
- `tests/`: `node:test` + jsdom, with Leaflet, Chart.js and `fetch` faked.

## Run

Locally, `docker compose -f compose.dev.yml up -d` at the repository root serves this folder on
http://localhost:5500 with `/api` proxied to the backend (see `docs/devops.md`). Tests: `npm test`.

The map tiles (CARTO), Leaflet and Chart.js load from CDNs.

The CARTO basemaps API key is a constant in `src/services/mapService.js`: it is public by design (every tile URL
carries it), so it is protected on CARTO's side instead, restricted to our domains, to basemaps and to a quota.
Without a key CARTO watermarks the tiles with "API key required".

The header switch toggles light and dark (`src/theme.js`; the first visit follows the system): the page, the
basemap (CARTO Voyager by day, Dark Matter by night) and the chart follow it. Where CARTO refuses the key, such as
on localhost if it isn't allowed there, the map falls back to OpenStreetMap's tiles instead of showing no background.
