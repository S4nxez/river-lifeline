import { test } from "node:test";
import assert from "node:assert/strict";
import { installLeaflet, site } from "./helpers/browser.js";
import { createMap } from "../src/services/mapService.js";

const leaflet = installLeaflet({ zoom: 2 });
const selected = [];
const sites = [
  site({ id: "amazonas", name: "Amazonas basin", level: "high", change: "+18%", lat: -3.1, lng: -60 }),
  site({ id: "delta", name: "Nile Delta", level: "medium", change: "+9%", lat: 30.9, lng: 31.2 }),
  site({ id: "valencia", name: "Valencia coast", level: "low", change: "+3%", lat: 39.5, lng: -0.4 }),
];
const controls = await createMap(sites, (id) => selected.push(id));

test("draws the map in the #map element and resizes it once laid out", () => {
  assert.equal(leaflet.mapContainer, "map");
  assert.equal(leaflet.invalidated, true);
});

test("places one marker per site, coloured by change level", () => {
  assert.deepEqual(
    leaflet.markers.map((marker) => [marker.latLng, marker.options.fillColor]),
    [
      [[-3.1, -60], "#e47759"],
      [[30.9, 31.2], "#e4b65c"],
      [[39.5, -0.4], "#68b6a3"],
    ],
  );
  assert.equal(leaflet.markers[1].tooltip, "Nile Delta · +9%");
});

test("clicking a marker reports its site", () => {
  leaflet.markers[2].handlers.click();

  assert.deepEqual(selected, ["valencia"]);
});

test("selectSite flies to the site, zooming in to at least 4, and opens its tooltip", () => {
  controls.selectSite("delta");

  assert.deepEqual(leaflet.flights.at(-1), { latLng: [30.9, 31.2], zoom: 4 });
  assert.equal(leaflet.markers[1].tooltipOpen, true);
});

test("selectSite ignores unknown sites", () => {
  const flights = leaflet.flights.length;

  controls.selectSite("atlantis");

  assert.equal(leaflet.flights.length, flights);
});
