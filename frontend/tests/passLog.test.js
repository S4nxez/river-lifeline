import { test } from "node:test";
import assert from "node:assert/strict";
import { installDom } from "./helpers/browser.js";
import { passLogScene, renderPassLog, stripLabel } from "../src/components/passLog.js";

const counts = (isolated, atRisk, connected) => ({
  monitored: 10,
  isolated,
  atRisk,
  connected,
  noData: 10 - isolated - atRisk - connected,
});
const passes = [
  {
    id: "a",
    acquiredOn: "2026-01-08",
    evaluated: true,
    track: 97,
    orbitDirection: "descending",
    riverLevelCm: 1100,
    counts: counts(0, 0, 10),
  },
  {
    id: "b",
    acquiredOn: "2026-06-25",
    evaluated: true,
    track: 161,
    orbitDirection: "ascending",
    riverLevelCm: 1200,
    counts: counts(1, 2, 7),
  },
  { id: "c", acquiredOn: "2026-06-25", evaluated: false, track: 169, orbitDirection: "descending", counts: null },
  {
    id: "d",
    acquiredOn: "2026-07-07",
    evaluated: true,
    track: 161,
    orbitDirection: "ascending",
    riverLevelCm: 900,
    counts: counts(2, 3, 5),
  },
];
const river = {
  readings: [
    { date: "2026-06-20", levelCm: 1250 },
    { date: "2026-06-21", levelCm: 1240 },
    // a 10-day hole in the readings: the line breaks
    { date: "2026-07-01", levelCm: 1000 },
    { date: "2026-07-02", levelCm: 990 },
  ],
};
const scene = passLogScene({
  passes,
  river,
  today: "2026-07-20",
  expected: [{ day: "2026-07-19", track: 161, orbitDirection: "ascending" }],
  width: 1000,
});

test("one strip per pass, in time order along the axis", () => {
  assert.equal(scene.strips.length, 4);
  assert.ok(scene.strips[0].x < scene.strips[1].x && scene.strips[1].x < scene.strips[3].x);
});

test("strips stack isolated at the base, then at risk, then connected, as tall as their share", () => {
  const strip = scene.strips[3];
  assert.deepEqual(
    strip.segments.map((segment) => segment.status),
    ["isolated", "at_risk", "connected"],
  );
  // All but the no-data share of the 130 px maximum
  assert.equal(Math.round(scene.base - strip.top), 130);
  assert.equal(Math.round(scene.base - scene.strips[1].top), 130);
});

test("strips lean the way the satellite flew", () => {
  const leanOf = (strip) => {
    const [bottomLeft, , , topLeft] = strip.outline.split(" ").map((point) => Number(point.split(",")[0]));
    return Math.sign(topLeft - bottomLeft);
  };
  assert.equal(leanOf(scene.strips[1]), 1);
  assert.equal(leanOf(scene.strips[0]), -1);
});

test("two passes on one day stand side by side; a partial one is only an outline", () => {
  assert.notEqual(scene.strips[1].x, scene.strips[2].x);
  assert.equal(scene.strips[2].evaluated, false);
  assert.deepEqual(scene.strips[2].segments, []);
});

test("a gap of more than 60 days is marked, months are ticked and today is drawn", () => {
  assert.equal(scene.gaps.length, 1);
  assert.ok(scene.gaps[0].x > scene.strips[0].x && scene.gaps[0].x < scene.strips[1].x);
  assert.ok(scene.ticks.some((tick) => tick.label === "Jan 2026"));
  assert.ok(scene.ticks.some((tick) => tick.label === "Jul"));
  assert.ok(scene.today > scene.strips[3].x);
  assert.equal(scene.ghosts.length, 1);
  assert.match(scene.ghosts[0].label, /Expected pass 19 Jul 2026 · track 161 ascending/);
});

test("the river line breaks where readings are missing, on a whole-metre scale", () => {
  assert.equal(scene.river.paths.length, 2);
  assert.deepEqual(
    scene.river.labels.map((label) => label.cm),
    [1400, 1200, 900],
  );
});

test("a strip says everything about its pass", () => {
  assert.equal(
    stripLabel(passes[1]),
    "25 Jun 2026 · track 161 ascending · isolated 1, at risk 2, connected 7 · river 1200 cm",
  );
  assert.equal(stripLabel(passes[2]), "25 Jun 2026 · track 169 descending · covers part of the area: image only");
});

test("draws the log; the selected pass is outlined with its date, and a strip picks its pass by click or key", () => {
  const container = installDom('<div id="log"></div>').querySelector("#log");
  const picked = [];
  renderPassLog(container, { passes, passId: "d", river, today: "2026-07-20", expected: [], animate: true }, (id) =>
    picked.push(id),
  );

  const strips = container.querySelectorAll(".log-strip");
  assert.equal(strips.length, 4);
  assert.ok(container.querySelector("svg").classList.contains("log-downlink"));
  const selected = container.querySelector(".log-strip.is-selected");
  assert.equal(selected.dataset.pass, "d");
  assert.equal(selected.querySelector(".log-selected-day").textContent, "7 JUL");
  assert.equal(strips[1].getAttribute("role"), "button");
  assert.equal(strips[1].getAttribute("tabindex"), "0");
  strips[1].dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
  strips[0].dispatchEvent(new window.KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  strips[0].dispatchEvent(new window.KeyboardEvent("keydown", { key: "a", bubbles: true }));
  assert.deepEqual(picked, ["b", "a"]);
  assert.match(container.querySelector(".log-legend").textContent, /River level \(cm\)/);
});

test("without the river, the log still draws its strips", () => {
  const empty = passLogScene({ passes, river: null, today: "2026-07-20", width: 600 });
  assert.equal(empty.river, null);
  assert.equal(empty.strips.length, 4);
});
