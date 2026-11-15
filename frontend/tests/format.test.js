import { test } from "node:test";
import assert from "node:assert/strict";
import {
  addDays,
  daysBetween,
  escapeHtml,
  formatChange,
  formatDay,
  formatLevel,
  formatMonthTick,
  formatNumber,
  formatShortDay,
  formatUtcTime,
  widthClassLabel,
} from "../src/format.js";

const CLASSES = [40, 60, 100, 200, 400];

test("formats ISO days without time zone shifts, long and short", () => {
  assert.equal(formatDay("2026-09-09"), "9 Sep 2026");
  assert.equal(formatDay("2026-12-31"), "31 Dec 2026");
  assert.equal(formatShortDay("2026-10-23"), "23 Oct");
});

test("reads the UTC time of a timestamp", () => {
  assert.equal(formatUtcTime("2026-10-30T22:41:00Z"), "22:41 UTC");
  assert.equal(formatUtcTime(null), null);
});

test("names the months of a time axis, with the year when one starts", () => {
  assert.equal(formatMonthTick("2026-03-01"), "Mar");
  assert.equal(formatMonthTick("2027-01-01"), "Jan 2027");
});

test("counts and adds days across months and years", () => {
  assert.equal(daysBetween("2026-10-30", "2026-11-11"), 12);
  assert.equal(daysBetween("2026-11-11", "2026-10-30"), -12);
  assert.equal(addDays("2026-12-25", 12), "2027-01-06");
});

test("numbers in English, at most one decimal", () => {
  assert.equal(formatNumber(1234.56), "1,234.6");
  assert.equal(formatNumber(797), "797");
});

test("changes carry a typographic sign, or say there is none", () => {
  assert.equal(formatChange(3), "+3");
  assert.equal(formatChange(-132, "cm"), "−132 cm");
  assert.equal(formatChange(0.02, "cm"), "no change");
});

test("river levels in centimetres", () => {
  assert.equal(formatLevel(797), "797 cm");
  assert.equal(formatLevel(1180), "1,180 cm");
  assert.equal(formatLevel(null), "—");
});

test("route widths become the area's width classes", () => {
  assert.equal(widthClassLabel(0, "at_risk", CLASSES), "< 40 m");
  assert.equal(widthClassLabel(40, "at_risk", CLASSES), "40–60 m");
  assert.equal(widthClassLabel(100, "connected", CLASSES), "100–200 m");
  assert.equal(widthClassLabel(400, "connected", CLASSES), "≥ 400 m");
  assert.equal(widthClassLabel(150, "connected", CLASSES), "150 m");
  assert.equal(widthClassLabel(60, "connected", []), "60 m");
});

test("without a width the label says why: no route, not verified, or not seen", () => {
  assert.equal(widthClassLabel(null, "isolated", CLASSES), "no route");
  assert.equal(widthClassLabel(null, "at_risk", CLASSES, "no_route_unconfirmed"), "no route");
  assert.equal(widthClassLabel(null, "at_risk", CLASSES, "route_unverified"), "not verified");
  assert.equal(widthClassLabel(null, "no_data", CLASSES, "outside_coverage"), "—");
});

test("text from the data is escaped for HTML", () => {
  assert.equal(escapeHtml(`<b>"Tefé" & 'co'</b>`), "&lt;b&gt;&quot;Tefé&quot; &amp; &#39;co&#39;&lt;/b&gt;");
});
