import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { installDom } from "./helpers/browser.js";
import { imageryNote, renderLayerControls } from "../src/components/layerControls.js";

const BOUNDS = [
  [-3.9, -65.2],
  [-2.9, -64.3],
];
const layer = (id, label) => ({
  id,
  label,
  url: `./${id}.png`,
  bounds: BOUNDS,
  defaultVisible: true,
  defaultOpacity: 1,
});
const passes = [
  { id: "p2", acquiredOn: "2026-10-23", platform: "NISAR", track: 81, evaluated: true, coverage: 0.97 },
  { id: "p1", acquiredOn: "2026-10-19", platform: "NISAR", track: 81, evaluated: false, coverage: 0.55 },
];

let container;
let calls;

function render(overrides = {}) {
  const state = {
    passes,
    passId: "p2",
    basemap: "map",
    imagery: [layer("radar_hh", "Radar (HH)"), layer("water", "Open water")],
    choices: { radar: "radar_hh", opacity: 0.6, overlays: { water: true } },
    unavailable: new Set(),
    live: true,
    ...overrides,
  };
  renderLayerControls(container, state, {
    onBasemap: (basemap) => calls.push(["basemap", basemap]),
    onRadar: (radar) => calls.push(["radar", radar]),
    onOpacity: (opacity) => calls.push(["opacity", opacity]),
    onOverlay: (id, visible) => calls.push(["overlay", id, visible]),
  });
}

const pressed = (name) =>
  [...container.querySelectorAll(`[data-${name}]`)].filter((b) => b.getAttribute("aria-pressed") === "true");

beforeEach(() => {
  container = installDom('<div id="layers"></div>').querySelector("#layers");
  calls = [];
});

test("the basemap is map or satellite, one at a time", () => {
  render({ basemap: "satellite" });

  assert.deepEqual(
    pressed("basemap").map((b) => b.dataset.basemap),
    ["satellite"],
  );
  container.querySelector('[data-basemap="map"]').click();
  assert.deepEqual(calls, [["basemap", "map"]]);
});

test("the radar is off, NISAR or Sentinel-1; a source the pass doesn't serve can't be picked", () => {
  render();
  const sentinel = container.querySelector('[data-radar="sentinel1_vv"]');

  assert.deepEqual(
    pressed("radar").map((b) => b.textContent),
    ["NISAR"],
  );
  assert.equal(sentinel.disabled, true);
  assert.equal(sentinel.title, "Not served for this pass");
  container.querySelector('[data-radar=""]').click();
  assert.deepEqual(calls, [["radar", null]]);
});

test("the opacity slider reports a fraction and shows the percentage as it moves", () => {
  render();
  const slider = container.querySelector("[data-opacity]");

  assert.equal(slider.value, "60");
  slider.value = "35";
  slider.dispatchEvent(new window.Event("input"));
  assert.deepEqual(calls, [["opacity", 0.35]]);
  assert.equal(container.querySelector("output").textContent, "35 %");
});

test("the opacity slider rests while no radar is drawn", () => {
  render({ choices: { radar: null, opacity: 1, overlays: {} } });

  assert.equal(container.querySelector("[data-opacity]").disabled, true);
});

test("every overlay of the pass is a checkbox named from the catalog", () => {
  render();
  const water = container.querySelector('[data-overlay="water"]');

  assert.equal(water.checked, true);
  assert.equal(water.parentElement.textContent, "Open water");
  water.checked = false;
  water.dispatchEvent(new window.Event("change"));
  assert.deepEqual(calls, [["overlay", "water", false]]);
});

test("an image that failed to load is flagged where it is chosen", () => {
  render({ unavailable: new Set(["radar_hh", "water"]) });

  assert.match(container.textContent, /The radar image could not be loaded/);
  assert.match(container.querySelector('[data-overlay="water"]').parentElement.textContent, /unavailable/);
});

test("the note explains an empty map: demo data, a pass not judged, no images or a pass that failed", () => {
  assert.match(imageryNote({ live: false, imagery: [], pass: null }), /Demo data/);
  assert.match(imageryNote({ live: true, imagery: [], pass: passes[1] }), /only 55 % of the area: not enough to judge/);
  assert.match(imageryNote({ live: true, imagery: [], pass: passes[0] }), /serves no images for this pass/);
  assert.match(imageryNote({ live: true, imagery: [], pass: passes[0], failed: true }), /could not be loaded/);
  assert.equal(imageryNote({ live: true, imagery: [layer("water", "Water")], pass: passes[0] }), "");
});

test("the pass is picked with the mission clock, never in the layers panel", () => {
  render();

  assert.equal(container.querySelector("select"), null);
  assert.equal(container.querySelector("[data-pass]"), null);
});
