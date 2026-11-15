import { test } from "node:test";
import assert from "node:assert/strict";
import { installDom } from "./helpers/browser.js";
import { expectedPasses, neighbourPass, passForDay, renderClock, selectablePasses } from "../src/components/clock.js";

const pass = (id, acquiredOn, extra = {}) => ({
  id,
  acquiredOn,
  evaluated: true,
  track: 161,
  orbitDirection: "ascending",
  ...extra,
});
const passes = [
  pass("a", "2026-10-06", { track: 97, orbitDirection: "descending" }),
  pass("b", "2026-10-11"),
  pass("partial", "2026-10-15", { evaluated: false, track: 169 }),
  pass("c", "2026-10-23"),
  pass("d", "2026-10-30", { track: 97, orbitDirection: "descending" }),
];

test("a day shows the latest evaluated pass on or before it, else the first one", () => {
  assert.equal(passForDay(passes, "2026-10-23").id, "c");
  assert.equal(passForDay(passes, "2026-10-20").id, "b");
  assert.equal(passForDay(passes, "2026-01-01").id, "a");
  assert.equal(passForDay([], "2026-01-01"), null);
});

test("‹ and › step between evaluated passes, skipping partial ones", () => {
  assert.equal(neighbourPass(passes, "b", 1).id, "c");
  assert.equal(neighbourPass(passes, "c", -1).id, "b");
  assert.equal(neighbourPass(passes, "d", 1), null);
  assert.equal(neighbourPass(passes, "a", -1), null);
  assert.equal(neighbourPass(passes, "nowhere", 1), null);
});

test("NISAR is due back on each full track every 12 days, from today on, soonest first", () => {
  assert.deepEqual(expectedPasses(passes, "2026-11-15"), [
    { day: "2026-11-16", track: 161, orbitDirection: "ascending" },
    { day: "2026-11-23", track: 97, orbitDirection: "descending" },
  ]);
  // A track seen today is next due in one cycle
  assert.equal(expectedPasses([pass("x", "2026-11-15")], "2026-11-15")[0].day, "2026-11-27");
});

test("the clock steps, picks a day and plays, with the ends disabled", () => {
  const container = installDom('<div id="c"></div>').querySelector("#c");
  const calls = [];
  renderClock(
    container,
    { passes, passId: "d", day: "2026-10-30", today: "2026-11-15", playing: false },
    {
      onDay: (day) => calls.push(["day", day]),
      onStep: (step) => calls.push(["step", step]),
      onPlay: () => calls.push(["play"]),
    },
  );

  const [before, after] = container.querySelectorAll(".clock-step");
  const field = container.querySelector(".clock-date");
  assert.equal(after.disabled, true);
  assert.equal(before.disabled, false);
  // Only the days of the passes it can show, newest first, the one on screen selected
  assert.deepEqual(
    [...field.options].map((option) => option.value),
    ["2026-10-30", "2026-10-23", "2026-10-11", "2026-10-06"],
  );
  assert.equal(field.value, "2026-10-30");
  before.click();
  field.value = "2026-10-11";
  field.dispatchEvent(new window.Event("change"));
  container.querySelector(".clock-play").click();
  assert.deepEqual(calls, [["step", -1], ["day", "2026-10-11"], ["play"]]);
});

test("while playing, the button says Pause and is pressed", () => {
  const container = installDom('<div id="c"></div>').querySelector("#c");
  renderClock(
    container,
    { passes, passId: "b", day: "2026-10-11", today: "2026-11-15", playing: true },
    {
      onDay() {},
      onStep() {},
      onPlay() {},
    },
  );

  const play = container.querySelector(".clock-play");
  assert.equal(play.textContent, "Pause");
  assert.equal(play.getAttribute("aria-pressed"), "true");
});

test("only evaluated passes with both the NISAR and the Sentinel-1 image can be picked", () => {
  const both = ["radar_hh", "sentinel1_vv", "water"];
  const listed = [
    pass("full", "2026-10-06", { layerIds: both }),
    pass("no-s1", "2026-10-11", { layerIds: ["radar_hh", "water"] }),
    pass("partial", "2026-10-15", { evaluated: false, layerIds: both }),
    pass("also-full", "2026-10-23", { layerIds: both }),
  ];

  assert.deepEqual(
    selectablePasses(listed).map((item) => item.id),
    ["full", "also-full"],
  );
  assert.equal(passForDay(listed, "2026-10-20").id, "full");
  assert.equal(neighbourPass(listed, "full", 1).id, "also-full");
  // Nothing with every image (fixtures, demo data): the evaluated passes, so the clock is never empty
  assert.deepEqual(
    selectablePasses(passes).map((item) => item.id),
    ["a", "b", "c", "d"],
  );
});
