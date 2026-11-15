import { test } from "node:test";
import assert from "node:assert/strict";
import { community, installLeaflet } from "./helpers/browser.js";
import {
  SATELLITE_URL,
  createMap,
  imagesAreSharp,
  markerSize,
  tileUrl,
  tooltipFor,
} from "../src/services/mapService.js";

const leaflet = installLeaflet({ zoom: 9 });
const selected = [];
const imageErrors = [];
const area = {
  id: "tefe",
  name: "Tefé",
  center: { lat: -3.4, lng: -64.75 },
  radiusKm: 55,
  bounds: [
    [-3.9, -65.2],
    [-2.9, -64.3],
  ],
  widthClassesM: [40, 60, 100, 200, 400],
};
const hub = { id: "tefe", name: "Tefé", kind: "hub", lat: -3.354, lng: -64.711 };
const communities = [
  community({
    id: "nogueira",
    name: "Nogueira",
    lat: -3.295,
    lng: -64.86,
    latest: { status: "isolated", routeWidthM: null },
  }),
  community({ id: "uarini", name: "Uarini", lat: -2.99, lng: -65.113, latest: { status: "at_risk", routeWidthM: 40 } }),
  community({
    id: "alvaraes",
    name: "Alvarães",
    lat: -3.221,
    lng: -64.804,
    latest: { status: "connected", routeWidthM: 400 },
  }),
  community({ id: "caiambe", name: "Caiambé", lat: -3.545, lng: -64.4, latest: null }),
];
const controls = await createMap({
  area,
  hub,
  communities,
  onSelect: (id) => selected.push(id),
  theme: "light",
  onImageError: (id) => imageErrors.push(id),
});
const communityMarkers = [...leaflet.markers];
const hubMarker = leaflet.hub;
const BOUNDS = [
  [-3.9, -65.2],
  [-2.9, -64.3],
];
const image = (id, opacity = 1, widthPx = null) => ({
  id,
  url: `./api/v1/layers/${id}.png`,
  bounds: BOUNDS,
  opacity,
  widthPx,
});
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

test("draws the map in #map, framed on the area's bounds once laid out, wheel zoom on in quarter steps", () => {
  assert.equal(leaflet.mapContainer, "map");
  assert.deepEqual(leaflet.view.bounds, area.bounds);
  assert.equal(leaflet.invalidated, true);
});

test("keeps the zoom buttons bottom left, above the image plate", () => {
  assert.deepEqual(leaflet.zoomControl, { position: "bottomleft" });
});

test("the radar images get their own pane, between the basemap and the markers", () => {
  assert.equal(leaflet.panes.imagery.style.zIndex, 300);
});

test("the watched area is drawn as a sensor footprint: its circle, dashed, never in the way", () => {
  const [circle] = leaflet.circles;
  assert.deepEqual(circle.latLng, [-3.4, -64.75]);
  assert.equal(circle.options.radius, 55_000);
  assert.equal(circle.options.className, "aoi-footprint");
  assert.equal(circle.options.dashArray, "2 6");
  assert.equal(circle.options.interactive, false);
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

test("the hub is a white diamond above everything, named as where routes are traced to", () => {
  assert.deepEqual(hubMarker.latLng, [-3.354, -64.711]);
  assert.equal(hubMarker.options.icon.className, "hub-marker");
  assert.match(hubMarker.options.icon.html, /hub-diamond/);
  assert.equal(hubMarker.tooltip, "<b>Tefé</b><br>Hub town: routes are traced to here");
  assert.ok(
    hubMarker.options.zIndexOffset > Math.max(...communityMarkers.map((marker) => marker.options.zIndexOffset)),
  );
});

test("each community is its status glyph with a thin white outline, the serious ones on top", () => {
  assert.deepEqual(
    communityMarkers.map((marker) => [marker.latLng, /glyph-(\w+) /.exec(marker.options.icon.html)[1]]),
    [
      [[-3.295, -64.86], "isolated"],
      [[-2.99, -65.113], "at_risk"],
      [[-3.221, -64.804], "connected"],
      [[-3.545, -64.4], "no_data"],
    ],
  );
  assert.ok(communityMarkers.every((marker) => marker.options.icon.html.includes("glyph-outlined")));
  const offsets = communityMarkers.map((marker) => marker.options.zIndexOffset);
  assert.ok(offsets[0] > offsets[1] && offsets[1] > offsets[2] && offsets[2] > offsets[3]);
});

test("a community's tooltip names it, its status and its route's narrowest point", () => {
  assert.equal(communityMarkers[1].tooltip, "<b>Uarini</b> · At risk<br>Narrowest point 40–60 m");
  assert.equal(communityMarkers[0].tooltip, "<b>Nogueira</b> · Isolated");
  assert.equal(
    tooltipFor(community({ name: "<i>x</i>", latest: { routeWidthM: null } })),
    "<b>&lt;i&gt;x&lt;/i&gt;</b> · At risk",
  );
});

test("clicking a community marker reports it", () => {
  communityMarkers[2].handlers.click();

  assert.deepEqual(selected, ["alvaraes"]);
});

test("selectCommunity rings it, raises it, flies to it at zoom 12 or more and opens its tooltip", () => {
  controls.selectCommunity("uarini");

  assert.deepEqual(leaflet.flights.at(-1), { latLng: [-2.99, -65.113], zoom: 12 });
  assert.equal(communityMarkers[1].tooltipOpen, true);
  assert.match(communityMarkers[1].options.icon.className, /is-selected/);
  assert.ok(communityMarkers[1].options.zIndexOffset > communityMarkers[0].options.zIndexOffset);

  // Picking another one takes the ring off the first; without flying, the map stays where it is
  const flights = leaflet.flights.length;
  controls.selectCommunity("nogueira", { fly: false });
  assert.doesNotMatch(communityMarkers[1].options.icon.className, /is-selected/);
  assert.match(communityMarkers[0].options.icon.className, /is-selected/);
  assert.equal(leaflet.flights.length, flights);
});

test("selectCommunity ignores unknown ids, the hub included", () => {
  const flights = leaflet.flights.length;

  controls.selectCommunity("tefe");
  controls.selectCommunity("atlantis");

  assert.equal(leaflet.flights.length, flights);
});

test("setVisibleIds shows only those communities, and all of them back with null", () => {
  communityMarkers.forEach((marker) => (marker.added = false));
  controls.setVisibleIds(["nogueira", "uarini"]);
  assert.deepEqual(
    shownMarkers().map((marker) => marker.latLng),
    [
      [-3.295, -64.86],
      [-2.99, -65.113],
    ],
  );

  communityMarkers.forEach((marker) => (marker.added = false));
  controls.setVisibleIds(null);
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

test("images turn crisp once one of their pixels covers a screen pixel", () => {
  assert.equal(imagesAreSharp(15, [20, 40]), true);
  assert.equal(imagesAreSharp(30, [20, 40]), false);
  assert.equal(imagesAreSharp(1, []), false);

  // 0.9° of longitude in 5,000 px is about 20 m a pixel: sharp from about zoom 13 at this latitude
  controls.showImagery([image("water", 0.8, 5000)]);
  leaflet.zoomTo(10);
  assert.equal(leaflet.containerClasses.has("imagery-sharp"), false);
  leaflet.zoomTo(14);
  assert.equal(leaflet.containerClasses.has("imagery-sharp"), true);
  leaflet.zoomTo(9);
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

  controls.setCommunities([
    community({ id: "uarini", name: "Uarini", latest: { status: "isolated", routeWidthM: null } }),
  ]);

  assert.equal(leaflet.markers.length, before + 1);
  assert.equal(leaflet.markers.at(-1).tooltip, "<b>Uarini</b> · Isolated");
});

// Swaps the map's tiles for good, so it comes after the basemap tests
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
  assert.ok(leaflet.containerClasses.has("map-fallback"));

  controls.setTheme("dark");
  assert.equal(osm.url, "https://tile.openstreetmap.org/{z}/{x}/{y}.png");

  // The satellite photo needs no darkening; back on the map, OpenStreetMap is still the one used
  controls.setBasemap("satellite");
  assert.equal(leaflet.containerClasses.has("map-fallback"), false);
  controls.setBasemap("map");
  assert.equal(leaflet.tiles.at(-1).url, "https://tile.openstreetmap.org/{z}/{x}/{y}.png");
});

test("markerSize shrinks the glyphs as the map zooms out, between 6 and 14 px", () => {
  assert.deepEqual([4, 8, 9, 10, 11, 12, 15].map(markerSize), [6, 6, 8, 10, 12, 14, 14]);
});

test("the glyphs follow the zoom; the hub keeps its size", () => {
  const current = leaflet.markers.at(-1);

  leaflet.zoomTo(7);
  assert.equal(current.options.icon.iconSize[0], 6);
  leaflet.zoomTo(12);
  assert.equal(current.options.icon.iconSize[0], 14);
  assert.match(current.options.icon.html, /width="14"/);
  assert.equal(hubMarker.options.icon.iconSize[0], 16);
});

test("with less motion asked for, the map jumps to a community instead of flying", async () => {
  const fresh = installLeaflet({ zoom: 9 });
  globalThis.matchMedia = (query) => ({ matches: query.includes("reduce") });
  try {
    const map = await createMap({ area, hub, communities, onSelect: () => {} });
    map.selectCommunity("uarini");
    assert.equal(fresh.flights.length, 0);
    assert.deepEqual(fresh.view, { center: [-2.99, -65.113], zoom: 12 });
  } finally {
    delete globalThis.matchMedia;
  }
});

test("without bounds the map frames every place, and a single place by its centre", async () => {
  const fresh = installLeaflet();
  await createMap({ area: { center: null, bounds: null }, hub, communities, onSelect: () => {} });
  assert.equal(fresh.view.bounds.length, 5);
  assert.equal(fresh.circles.length, 0);

  const lone = installLeaflet();
  await createMap({
    area: { center: null, bounds: null },
    hub: null,
    communities: [communities[0]],
    onSelect: () => {},
  });
  assert.deepEqual(lone.view, { center: [-3.295, -64.86], zoom: 9 });
});
