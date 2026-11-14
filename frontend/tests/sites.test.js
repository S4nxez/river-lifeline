// The demo data is what users see when the API is down: it must keep the shape the components expect.
import { test } from "node:test";
import assert from "node:assert/strict";
import { observationDates, sites } from "../src/data/sites.js";

test("every site has a unique id", () => {
  const ids = sites.map((site) => site.id);
  assert.equal(new Set(ids).size, ids.length);
});

test("every site has a known change level and a confidence between 0 and 1", () => {
  for (const site of sites) {
    assert.ok(["high", "medium", "low"].includes(site.level), `${site.id}: level ${site.level}`);
    assert.ok(site.confidence >= 0 && site.confidence <= 1, `${site.id}: confidence ${site.confidence}`);
  }
});

test("every site has valid coordinates", () => {
  for (const site of sites) {
    assert.ok(site.lat >= -90 && site.lat <= 90, `${site.id}: lat ${site.lat}`);
    assert.ok(site.lng >= -180 && site.lng <= 180, `${site.id}: lng ${site.lng}`);
  }
});

test("every series has one value per observation date", () => {
  for (const site of sites) {
    assert.equal(site.vv.length, observationDates.length, `${site.id}: vv`);
    assert.equal(site.vh.length, observationDates.length, `${site.id}: vh`);
  }
});
