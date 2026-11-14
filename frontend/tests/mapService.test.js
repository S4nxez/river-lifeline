import { test } from "node:test";
import assert from "node:assert/strict";
import { community, installLeaflet } from "./helpers/browser.js";
import { createMap, tileUrl } from "../src/services/mapService.js";

const leaflet = installLeaflet({ zoom: 9 });
const selected = [];
const hub = { id: "tefe", name: "Tefé", kind: "hub", lat: -3.354, lng: -64.711 };
const communities = [
  community({ id: "nogueira", name: "Nogueira", lat: -3.295, lng: -64.86, latest: { status: "isolated" } }),
  community({ id: "uarini", name: "Uarini", lat: -2.99, lng: -65.113, latest: { status: "at_risk" } }),
  community({ id: "alvaraes", name: "Alvarães", lat: -3.221, lng: -64.804, latest: { status: "connected" } }),
  community({ id: "caiambe", name: "Caiambé", lat: -3.545, lng: -64.4, latest: null }),
];
const controls = await createMap(hub, communities, (id) => selected.push(id));
const [hubMarker, ...communityMarkers] = leaflet.markers;

test("draws the map in #map and resizes it once laid out", () => {
  assert.equal(leaflet.mapContainer, "map");
  assert.equal(leaflet.invalidated, true);
});

test("starts on the CARTO day map (Voyager) with the API key, keeping its attribution", () => {
  const [tiles] = leaflet.tiles;
  assert.equal(tiles.url, tileUrl("light"));
  assert.match(tiles.url, /^https:\/\/\{s\}\.basemaps\.cartocdn\.com\/rastertiles\/voyager\/.*\.png\?key=cb1_\w+$/);
  assert.match(tiles.options.attribution, /OpenStreetMap.*CARTO/);
});

test("tileUrl gives Dark Matter for the dark theme and the day map for anything else", () => {
  assert.match(tileUrl("dark"), /basemaps\.cartocdn\.com\/dark_all\/.*\?key=cb1_/);
  assert.equal(tileUrl("sepia"), tileUrl("light"));
});

test("setTheme switches the basemap between day and night", () => {
  const [tiles] = leaflet.tiles;

  controls.setTheme("dark");
  assert.equal(tiles.url, tileUrl("dark"));

  controls.setTheme("light");
  assert.equal(tiles.url, tileUrl("light"));
});

test("marks the hub apart from the communities", () => {
  assert.deepEqual(hubMarker.latLng, [-3.354, -64.711]);
  assert.equal(hubMarker.tooltip, "Tefé · hub");
});

test("colours each community by its status", () => {
  assert.deepEqual(
    communityMarkers.map((marker) => [marker.latLng, marker.options.fillColor]),
    [
      [[-3.295, -64.86], "#b42318"],
      [[-2.99, -65.113], "#a15c07"],
      [[-3.221, -64.804], "#0b7a52"],
      [[-3.545, -64.4], "#8a8f96"],
    ],
  );
  assert.equal(communityMarkers[1].tooltip, "Uarini · At risk");
});

test("clicking a community marker reports it", () => {
  communityMarkers[2].handlers.click();

  assert.deepEqual(selected, ["alvaraes"]);
});

test("selectCommunity flies to it, zooming in to at least 11, and opens its tooltip", () => {
  controls.selectCommunity("uarini");

  assert.deepEqual(leaflet.flights.at(-1), { latLng: [-2.99, -65.113], zoom: 11 });
  assert.equal(communityMarkers[1].tooltipOpen, true);
});

test("selectCommunity ignores unknown ids, the hub included", () => {
  const flights = leaflet.flights.length;

  controls.selectCommunity("tefe");
  controls.selectCommunity("atlantis");

  assert.equal(leaflet.flights.length, flights);
});

// Last: it swaps the map's tiles for good
test("when CARTO refuses a tile it falls back to OpenStreetMap once, which the dark theme then darkens with CSS", () => {
  const [carto] = leaflet.tiles;
  const warn = console.warn;
  console.warn = () => {};
  try {
    carto.handlers.tileerror();
    carto.handlers.tileerror();
  } finally {
    console.warn = warn;
  }

  assert.equal(carto.removed, true);
  assert.equal(leaflet.tiles.length, 2);
  const osm = leaflet.tiles[1];
  assert.equal(osm.url, "https://tile.openstreetmap.org/{z}/{x}/{y}.png");
  assert.match(osm.options.attribution, /OpenStreetMap/);
  assert.deepEqual(leaflet.containerClasses, ["map-fallback"]);

  controls.setTheme("dark");
  assert.equal(osm.url, "https://tile.openstreetmap.org/{z}/{x}/{y}.png");
});
