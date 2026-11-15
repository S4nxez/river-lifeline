# River Lifeline — frontend

Vanilla JavaScript page (ES modules, no build step) that shows which riverside communities around Tefé can still
reach the town by water, on every NISAR pass, with the Sentinel-1 image of the pass to compare against.

It looks like a mission control room: night colours (a day theme too), telemetry labels in mono capitals, the day's
result written as a sentence, a hand-drawn pass log, and a canopy of leaves over the name. Everything is drawn with
SVG and CSS: no chart or UI library, only Leaflet for the map.

It only talks to the backend, with relative URLs (the app is served under a path prefix):

| Request                                             | Used for                                                          |
| --------------------------------------------------- | ----------------------------------------------------------------- |
| `GET ./api/v1/meta`                                 | The layer catalog: each layer's name, default visibility, opacity |
| `GET ./api/v1/aois/{aoi}`                           | The area: name, circle, bounds, width classes, gauge and the hub  |
| `GET ./api/v1/aois/{aoi}/summary`                   | The latest evaluated pass and when the record was last updated    |
| `GET ./api/v1/aois/{aoi}/passes`                    | Every pass, with its orbit and counts: the clock and the pass log |
| `GET ./api/v1/aois/{aoi}/passes/{pass}`             | A pass's images (`layers`) and the plate of its radar image       |
| `GET ./api/v1/aois/{aoi}/passes/{pass}/communities` | Every monitored community's status on that pass (`latest` first)  |
| `GET ./api/v1/aois/{aoi}/passes/{pass}/layers/*`    | The images themselves, through the `url` of each layer            |
| `GET ./api/v1/aois/{aoi}/communities/{id}`          | One community's card: monitored or not, its route on every pass   |
| `GET ./api/v1/aois/{aoi}/river-level`               | The gauge's daily readings: the line behind the pass log          |
| `GET ./api/v1/aois/{aoi}/passes/{pass}/export`      | "Download this day" (CSV or GeoJSON, written by the backend)      |

The area comes from the page URL (`?aoi=...`, default `tefe`). `src/services/api.js` maps the responses (contract 2
of CONTRACTS.md) into the page's own view model. When the API can't be reached, or has no communities yet, the page
shows the demo data of `src/data/demo.js` and says so in the header. Nothing about the area is written in the page:
its name, circle, bounds, width classes and gauge all come from the API, and what the API doesn't give (water area,
population, distance to water...) is simply not shown.

## The page

- **Mast:** the name, large and centred under the canopy, then the mission clock: "Situation on ‹ day ›" (a day shows
  the latest evaluated pass on or before it) and Play record (one pass every 1.3 s). The day and the selected community
  live in the URL hash (`#d=2026-10-23&c=<id>`), so a link opens the same view.
- **Scene:** the day's sentence above the map, and the community list beside it (search, filters, download). The
  filters narrow the map too.
- **Pass log:** one strip per pass, leaning the way the satellite flew, split by status, over the river level, with
  NISAR's next expected passes (its last pass on each track plus 12-day cycles).
- **Desk:** the pass in figures, with their change since the pass before, and the selected community's dossier.

## The map

On it: the communities as status glyphs (a shape per status, never colour alone) that shrink as you zoom out, the
hub as a white diamond, the watched area's circle, the layers panel (folded at first), the plate of the radar image
and the map key. The layers panel picks the basemap (map or satellite photo), the radar image and its opacity, and the
analysis overlays the pass serves (water, change...), named and defaulted from `/meta`.

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
- `src/status.js`: what each status means (labels, shapes, colours, severity order).
- `src/format.js`: dates, width classes, levels and changes for display.
- `src/hashState.js`: the day and community kept in the URL hash.
- `src/theme.js`: the light/dark switch.
- `src/imagery.js`: which images the map draws (radar sources, overlays, the user's choices).
- `src/services/api.js`: the backend calls and the demo fallback. `src/services/mapService.js`: Leaflet map.
- `src/components/`: the headline, the mission clock, the community list, the layers panel, the image plate, the map
  key, the pass log, the ledger ("On this pass"), the dossier, and the status glyphs they share.
- `tests/`: `node:test` + jsdom, with Leaflet and `fetch` faked.

## Run

Locally, `docker compose -f compose.dev.yml up -d` at the repository root serves this folder on
http://localhost:5500 with `/api` proxied to the backend (see `docs/devops.md`). Tests: `npm test`.

The map tiles (CARTO), Leaflet and the fonts (Google Fonts: Saira Stencil One, Atkinson Hyperlegible, Martian Mono)
load from CDNs.

The CARTO basemaps API key is a constant in `src/services/mapService.js`: it is public by design (every tile URL
carries it), so it is protected on CARTO's side instead, restricted to our domains, to basemaps and to a quota.
Without a key CARTO watermarks the tiles with "API key required".

The header switch toggles light and dark (`src/theme.js`; the first visit follows the system): the page, the
basemap (CARTO Voyager by day, Dark Matter by night) and the chart follow it. Where CARTO refuses the key, such as
on localhost if it isn't allowed there, the map falls back to OpenStreetMap's tiles instead of showing no background.
