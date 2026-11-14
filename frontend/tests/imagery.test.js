import { test } from "node:test";
import assert from "node:assert/strict";
import { RADAR_SOURCES, imageryChoices, layersToShow, overlaysOf } from "../src/imagery.js";

const BOUNDS = [
  [-3.9, -65.2],
  [-2.9, -64.3],
];
const layer = (id, defaultVisible = true, defaultOpacity = 1) => ({
  id,
  label: id,
  url: `./api/v1/${id}.png`,
  bounds: BOUNDS,
  defaultVisible,
  defaultOpacity,
});
const imagery = [layer("radar_hh", true, 1), layer("water", true, 0.8), layer("change", false, 0.9)];

test("NISAR is the contract's radar_hh layer and Sentinel-1 is expected as sentinel1_vv", () => {
  assert.deepEqual(
    RADAR_SOURCES.map(({ id, label }) => [id, label]),
    [
      ["radar_hh", "NISAR"],
      ["sentinel1_vv", "Sentinel-1"],
    ],
  );
});

test("the overlays are every layer that isn't a radar image", () => {
  assert.deepEqual(
    overlaysOf([...imagery, layer("sentinel1_vv")]).map(({ id }) => id),
    ["water", "change"],
  );
});

test("a first pass follows the catalog's defaults", () => {
  assert.deepEqual(imageryChoices(imagery), {
    radar: "radar_hh",
    opacity: 1,
    overlays: { water: true, change: false },
  });
});

test("without a radar shown by default, none is chosen", () => {
  assert.deepEqual(imageryChoices([layer("radar_hh", false), layer("water")]).radar, null);
  assert.deepEqual(imageryChoices([]), { radar: null, opacity: 1, overlays: {} });
});

test("another pass keeps the user's choices, and new overlays start with their default", () => {
  const previous = { radar: null, opacity: 0.4, overlays: { water: false } };

  assert.deepEqual(imageryChoices(imagery, previous), {
    radar: null,
    opacity: 0.4,
    overlays: { water: false, change: false },
  });
});

test("draws the chosen radar at the user's opacity, under the overlays turned on at theirs", () => {
  const choices = { radar: "radar_hh", opacity: 0.5, overlays: { water: true, change: false } };

  assert.deepEqual(layersToShow(choices, imagery), [
    { id: "radar_hh", url: "./api/v1/radar_hh.png", bounds: BOUNDS, opacity: 0.5 },
    { id: "water", url: "./api/v1/water.png", bounds: BOUNDS, opacity: 0.8 },
  ]);
});

test("a radar the pass doesn't serve draws nothing, and the overlays still show", () => {
  const choices = { radar: "sentinel1_vv", opacity: 1, overlays: { water: true } };

  assert.deepEqual(
    layersToShow(choices, imagery).map(({ id }) => id),
    ["water"],
  );
});
