// The demo data is what users see when the API is down: it must keep the API's shape and tell a coherent story.
import { test } from "node:test";
import assert from "node:assert/strict";
import { demoHistory, demoOverview } from "../src/data/demo.js";
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

test("the latest pass counts the statuses it found", () => {
  assert.equal(overview.latestPass.acquiredOn, "2026-09-09");
  assert.deepEqual(overview.latestPass.counts, { connected: 1, atRisk: 0, isolated: 2, noData: 1 });
});

test("an unknown community has no history", () => {
  assert.equal(demoHistory("atlantis"), null);
});
