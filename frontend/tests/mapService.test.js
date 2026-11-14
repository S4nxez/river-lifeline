import { test } from "node:test";
import assert from "node:assert/strict";
import { community, installLeaflet } from "./helpers/browser.js";
import { BASEMAP_URL, LABELS_URL, createMap } from "../src/services/mapService.js";

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

test("uses basemap tiles that need no API key, with their attribution", () => {
  assert.deepEqual(
    leaflet.tileLayers.map((layer) => layer.url),
    [BASEMAP_URL, LABELS_URL],
  );
  for (const { url } of leaflet.tileLayers) {
    assert.ok(url.startsWith("https://server.arcgisonline.com/"), url);
    assert.ok(!/key=/.test(url), url);
  }
  assert.match(leaflet.tileLayers[0].options.attribution, /Esri/);
});

test("marks the hub apart from the communities", () => {
  assert.deepEqual(hubMarker.latLng, [-3.354, -64.711]);
  assert.equal(hubMarker.tooltip, "Tefé · hub");
});

test("colours each community by its status", () => {
  assert.deepEqual(
    communityMarkers.map((marker) => [marker.latLng, marker.options.fillColor]),
    [
      [[-3.295, -64.86], "#e47759"],
      [[-2.99, -65.113], "#e4b65c"],
      [[-3.221, -64.804], "#68b6a3"],
      [[-3.545, -64.4], "#6d8491"],
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
