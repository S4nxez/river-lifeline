import { test } from "node:test";
import assert from "node:assert/strict";
import { formatDay, formatRiverLevel, formatWidth } from "../src/format.js";

test("formats ISO days without time zone shifts", () => {
  assert.equal(formatDay("2026-09-09"), "9 Sep 2026");
  assert.equal(formatDay("2026-01-01"), "1 Jan 2026");
  assert.equal(formatDay("2026-12-31"), "31 Dec 2026");
});

test("formats route widths, with a dash when there is no route", () => {
  assert.equal(formatWidth(60), "60 m");
  assert.equal(formatWidth(null), "—");
  assert.equal(formatWidth(undefined), "—");
});

test("formats the river gauge in metres", () => {
  assert.equal(formatRiverLevel(1180), "11.8 m");
  assert.equal(formatRiverLevel(980), "9.8 m");
  assert.equal(formatRiverLevel(null), "—");
});
