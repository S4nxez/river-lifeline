import { test } from "node:test";
import assert from "node:assert/strict";
import { community, installLeaflet } from "./helpers/browser.js";
import { SATELLITE_URL, createMap, markerRadius, tileUrl } from "../src/services/mapService.js";

const leaflet = installLeaflet({ zoom: 9 });
const selected = [];
const imageErrors = [];
const area = {
  id: "tefe",
  name: "Tefé",
  center: { lat: -3.4, lng: -64.75 },
  bounds: [
    [-3.9, -65.2],
    [-2.9, -64.3],
  ],
};
const hub = { id: "tefe", name: "Tefé", kind: "hub", lat: -3.354, lng: -64.711 };
const communities = [
  community({ id: "nogueira", name: "Nogueira", lat: -3.295, lng: -64.86, latest: { status: "isolated" } }),
  community({ id: "uarini", name: "Uarini", lat: -2.99, lng: -65.113, latest: { status: "at_risk" } }),
  community({ id: "alvaraes", name: "Alvarães", lat: -3.221, lng: -64.804, latest: { status: "connected" } }),
  community({ id: "caiambe", name: "Caiambé", lat: -3.545, lng: -64.4, latest: null }),
];
const controls = await createMap({
  area,
  hub,
  communities,
  onSelect: (id) => selected.push(id),
  onImageError: (id) => imageErrors.push(id),
});
const [hubMarker, ...communityMarkers] = leaflet.markers;
const BOUNDS = [
  [-3.9, -65.2],
  [-2.9, -64.3],
];
const image = (id, opacity = 1) => ({ id, url: `./api/v1/layers/${id}.png`, bounds: BOUNDS, opacity });
const onMap = () => leaflet.images.filter((overlay) => !overlay.removed).map((overlay) => overlay.url);
const shownMarkers = () => communityMarkers.filter((marker) => marker.added);

// The fake marker records being added to a group through the group itself
for (const marker of leaflet.markers) {
  const addTo = marker.addTo;
  marker.addTo = function (target) {
    this.added = true;
    return addTo.call(this, target);
  };
}

test("draws the map in #map, framed on the area's bounds, and resizes it once laid out", () => {
  assert.equal(leaflet.mapContainer, "map");
  assert.deepEqual(leaflet.view.bounds, area.bounds);
  assert.equal(leaflet.invalidated, true);
});

test("keeps the zoom buttons bottom right, away from the panels on the map", () => {
  assert.deepEqual(leaflet.zoomControl, { position: "bottomright" });
});

test("the radar images get their own pane, between the basemap and the markers", () => {
  assert.equal(leaflet.panes.imagery.style.zIndex, 300);
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

test("setVisibleStatuses shows only the communities in those statuses, and all of them back", () => {
  communityMarkers.forEach((marker) => (marker.added = false));
  controls.setVisibleStatuses(new Set(["isolated", "at_risk"]));
  assert.deepEqual(
    shownMarkers().map((marker) => marker.tooltip),
    ["Nogueira · Isolated", "Uarini · At risk"],
  );

  communityMarkers.forEach((marker) => (marker.added = false));
  controls.setVisibleStatuses(new Set(["isolated", "at_risk", "connected", "no_data"]));
  assert.equal(shownMarkers().length, 4);
});

test("showImagery draws the layers it is given, bottom to top, in the imagery pane", () => {
  controls.showImagery([image("radar_hh", 0.6), image("water", 0.8)]);

  assert.deepEqual(onMap(), ["./api/v1/layers/radar_hh.png", "./api/v1/layers/water.png"]);
  const [radar, water] = leaflet.images;
  assert.deepEqual(radar.bounds, BOUNDS);
  assert.equal(radar.opacity, 0.6);
  assert.equal(radar.options.pane, "imagery");
  assert.ok(water.frontCount >= 1);
});

test("showImagery keeps an image already on the map and only changes its opacity", () => {
  const created = leaflet.images.length;

  controls.showImagery([image("radar_hh", 0.3), image("water", 0.8)]);

  assert.equal(leaflet.images.length, created);
  assert.equal(leaflet.images[0].opacity, 0.3);
});

test("showImagery takes off the images no longer wanted", () => {
  controls.showImagery([image("water", 0.8)]);

  assert.deepEqual(onMap(), ["./api/v1/layers/water.png"]);
});

test("an image the API fails to serve is reported by its layer id", () => {
  controls.showImagery([image("change", 0.9)]);
  leaflet.images.at(-1).handlers.error();

  assert.deepEqual(imageErrors, ["change"]);
});

test("the satellite basemap replaces the map tiles, and the map comes back in the current theme", () => {
  controls.setTheme("dark");
  controls.setBasemap("satellite");
  const satellite = leaflet.tiles.at(-1);
  assert.equal(satellite.url, SATELLITE_URL);
  assert.match(satellite.options.attribution, /EOX/);
  assert.equal(leaflet.tiles.at(-2).removed, true);

  controls.setBasemap("map");
  assert.equal(satellite.removed, true);
  assert.equal(leaflet.tiles.at(-1).url, tileUrl("dark"));
  controls.setTheme("light");
});

test("setCommunities redraws the markers for another pass", () => {
  const before = leaflet.markers.length;

  controls.setCommunities([community({ id: "uarini", latest: { status: "isolated" } })]);

  assert.equal(leaflet.markers.length, before + 1);
  assert.equal(leaflet.markers.at(-1).tooltip, "Uarini · Isolated");
});

// Last: it swaps the map's tiles for good
test("when CARTO refuses a tile it falls back to OpenStreetMap once, which the dark theme then darkens with CSS", () => {
  const carto = leaflet.tiles.at(-1);
  const warn = console.warn;
  console.warn = () => {};
  try {
    carto.handlers.tileerror();
    carto.handlers.tileerror();
  } finally {
    console.warn = warn;
  }

  assert.equal(carto.removed, true);
  const osm = leaflet.tiles.at(-1);
  assert.equal(osm.url, "https://tile.openstreetmap.org/{z}/{x}/{y}.png");
  assert.match(osm.options.attribution, /OpenStreetMap/);
  assert.deepEqual([...leaflet.containerClasses], ["map-fallback"]);

  controls.setTheme("dark");
  assert.equal(osm.url, "https://tile.openstreetmap.org/{z}/{x}/{y}.png");

  // The satellite photo needs no darkening; back on the map, OpenStreetMap is still the one used
  controls.setBasemap("satellite");
  assert.deepEqual([...leaflet.containerClasses], []);
  controls.setBasemap("map");
  assert.equal(leaflet.tiles.at(-1).url, "https://tile.openstreetmap.org/{z}/{x}/{y}.png");
});

test("markerRadius shrinks the dots as the map zooms out, never below 2 px nor above 7 px", () => {
  assert.deepEqual([4, 7, 8, 9, 10, 11, 12, 15].map(markerRadius), [2, 2, 3, 4, 5, 6, 7, 7]);
});

test("the dots have no outline and follow the zoom, the hub 2 px larger", () => {
  // An earlier test redrew the communities as a single one: the marker drawn last
  const current = () => [leaflet.markers.at(-1)];
  assert.equal(hubMarker.options.stroke, false);
  assert.ok(current().every((marker) => marker.options.stroke === false));

  leaflet.zoomTo(7);
  assert.ok(current().every((marker) => marker.options.radius === 2));
  assert.equal(hubMarker.options.radius, 4);

  leaflet.zoomTo(12);
  assert.ok(current().every((marker) => marker.options.radius === 7));
  assert.equal(hubMarker.options.radius, 9);
});

test("without bounds the map frames every place, and a single place by its centre", async () => {
  const fresh = installLeaflet();
  await createMap({ area: { center: null, bounds: null }, hub, communities, onSelect: () => {} });
  assert.equal(fresh.view.bounds.length, 5);

  const lone = installLeaflet();
  await createMap({
    area: { center: null, bounds: null },
    hub: null,
    communities: [communities[0]],
    onSelect: () => {},
  });
  assert.deepEqual(lone.view, { center: [-3.295, -64.86], zoom: 9 });
});
