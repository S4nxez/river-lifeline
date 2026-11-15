// The small pieces around the map: status glyphs, the image plate, the map key, and the view kept in the URL hash.
import { test } from "node:test";
import assert from "node:assert/strict";
import { installDom } from "./helpers/browser.js";
import { glyph } from "../src/components/glyph.js";
import { plateText, renderPlate } from "../src/components/imagePlate.js";
import { renderMapKey } from "../src/components/mapKey.js";
import { hashFor, readHash } from "../src/hashState.js";

test("each status is drawn as its own shape, coloured by the theme", () => {
  assert.match(glyph("connected"), /class="glyph glyph-connected glyph-circle"/);
  assert.match(glyph("at_risk"), /<polygon/);
  assert.match(glyph("isolated", 14, "glyph-outlined"), /glyph-square glyph-outlined.*width="14"/);
  assert.match(glyph("no_data"), /glyph-ring/);
  // An unknown status draws as no data rather than failing
  assert.match(glyph("flooded"), /glyph-no_data/);
  assert.match(glyph("connected"), /aria-hidden="true"/);
});

const plate = {
  platform: "NISAR",
  band: "L",
  polarizations: ["HH", "HV"],
  track: 161,
  orbitDirection: "ascending",
  acquiredAt: "2026-10-23T10:02:00Z",
  pixelSizeM: 20,
  coverage: 1,
  product: "GCOV",
};

test("the plate reads like a mission's image caption", () => {
  const text = plateText(plate, "radar_hh");
  assert.equal(text.platform, "NISAR");
  assert.deepEqual(text.parts, [
    "L-band HH",
    "Track 161 ↑ ascending",
    "23 Oct 2026 10:02 UTC",
    "20 m grid",
    "100 % of the area",
  ]);
  assert.ok(plateText({ ...plate, product: "GCOV beta" }, null).parts.includes("beta product"));
});

test("with Sentinel-1 on the map the plate says it is the cross-check image", () => {
  const text = plateText(plate, "sentinel1_vv");
  assert.equal(text.platform, "Sentinel-1");
  assert.deepEqual(text.parts, ["C-band VV", "cross-check of the NISAR pass of 23 Oct 2026"]);
});

test("the plate hides when the pass has no image card", () => {
  const container = installDom('<p id="p"></p>').querySelector("#p");

  renderPlate(container, plate, "radar_hh");
  assert.equal(container.hidden, false);
  assert.equal(container.querySelector("b").textContent, "NISAR");

  renderPlate(container, null, "radar_hh");
  assert.equal(container.hidden, true);
});

test("the map key shows every status's glyph and names the hub", () => {
  const container = installDom('<div id="k"></div>').querySelector("#k");

  renderMapKey(container, "Tefé");

  assert.deepEqual(
    [...container.querySelectorAll("li")].map((item) => item.textContent),
    ["Isolated", "At risk", "Connected", "No data", "Tefé, the hub"],
  );
  assert.equal(container.querySelectorAll("svg.glyph").length, 4);
});

test("the view travels in the hash: day and community", () => {
  assert.deepEqual(readHash("#d=2026-10-23&c=osm123"), { day: "2026-10-23", communityId: "osm123" });
  assert.deepEqual(readHash("#d=yesterday"), { day: null, communityId: null });
  assert.deepEqual(readHash(""), { day: null, communityId: null });
  assert.equal(hashFor({ day: "2026-10-23", communityId: "a b" }), "#d=2026-10-23&c=a+b");
  assert.equal(hashFor({ day: null, communityId: null }), "");
});
