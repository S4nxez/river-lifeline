// The demo data is what users see when the API is down: it must keep the API's shape and tell a coherent story.
import { test } from "node:test";
import assert from "node:assert/strict";
import { demoHistory, demoOverview, demoPass, demoRiverLevel } from "../src/data/demo.js";
import { STATUSES } from "../src/status.js";

const overview = demoOverview();

test("has a hub and communities with unique ids and valid coordinates", () => {
  assert.equal(overview.hub.kind, "hub");
  const ids = overview.communities.map((community) => community.id);
  assert.equal(new Set(ids).size, ids.length);
  for (const { id, lat, lng } of [overview.hub, ...overview.communities]) {
    assert.ok(lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180, id);
  }
});

test("every assessment has a known status, and a width only while a route exists", () => {
  for (const { id } of overview.communities) {
    for (const { status, routeWidthM } of demoHistory(id).assessments) {
      assert.ok(status in STATUSES, `${id}: ${status}`);
      assert.equal(routeWidthM !== null, status === "connected" || status === "at_risk", `${id}: ${status}`);
    }
  }
});

test("each community's latest status is the last pass that saw it", () => {
  const caiambe = overview.communities.find((community) => community.id === "caiambe");

  assert.equal(caiambe.latest.status, "connected");
  assert.equal(caiambe.latest.acquiredOn, "2026-08-28");
});

test("each pass counts the statuses it found, and what changed since the one before", () => {
  const latest = overview.passes.at(-1);
  assert.equal(latest.acquiredOn, "2026-09-09");
  assert.deepEqual(latest.counts, { monitored: 4, connected: 1, atRisk: 0, isolated: 2, noData: 1, changed: 2 });
  assert.equal(overview.passes[0].counts.changed, 0);
});

test("the passes alternate their orbit, so the log's strips lean both ways", () => {
  assert.deepEqual(
    overview.passes.map((pass) => pass.orbitDirection),
    ["ascending", "descending", "ascending", "descending"],
  );
});

test("the demo gauge has a reading a day, ending on the last pass's level", () => {
  const { readings } = demoRiverLevel();
  assert.equal(readings[0].date, "2026-08-04");
  assert.deepEqual(readings.at(-1), { date: "2026-09-09", levelCm: 980 });
  assert.equal(readings.length, 37);
});

test("an unknown community has no history", () => {
  assert.equal(demoHistory("atlantis"), null);
});

test("the demo area brings its own connected width, and no bounds: the map frames its places", () => {
  assert.equal(overview.area.name, "Tefé");
  assert.equal(overview.area.connectedMinWidthM, 60);
  assert.equal(overview.area.bounds, null);
});

test("lists its passes oldest first, starting on the latest, without imagery", () => {
  assert.deepEqual(
    overview.passes.map((pass) => pass.acquiredOn),
    ["2026-08-04", "2026-08-16", "2026-08-28", "2026-09-09"],
  );
  assert.equal(overview.passId, "nisar-2026-09-09");
  assert.deepEqual(overview.imagery, []);
});

test("each pass shows every community as that pass saw it", () => {
  const pass = demoPass("nisar-2026-08-16");

  assert.deepEqual(
    pass.communities.map(({ id, latest }) => [id, latest.status, latest.acquiredOn]),
    [
      ["alvaraes", "connected", "2026-08-16"],
      ["uarini", "connected", "2026-08-16"],
      ["nogueira", "at_risk", "2026-08-16"],
      ["caiambe", "connected", "2026-08-16"],
    ],
  );
  assert.deepEqual(pass.imagery, []);
  assert.equal(demoPass("nisar-1999-01-01"), null);
});
