import { test } from "node:test";
import assert from "node:assert/strict";
import { installDom } from "./helpers/browser.js";
import {
  headline,
  isolationChance,
  renderEarlyWarningPanel,
  renderLimits,
  sortedCommunities,
} from "../src/components/earlyWarningPanel.js";
import { CHART_HEIGHT, forecastScene, renderLevelForecastChart } from "../src/components/levelForecastChart.js";
import { REPORT } from "./helpers/earlyWarning.js";

test("the headline says how early the warnings came, how many and how many were wrong", () => {
  assert.equal(
    headline(REPORT.hindcast),
    "Median warning: 24 days before the radar saw the cut-off; 4 of 6 communities warned; 1 false alarm.",
  );
  assert.equal(
    headline({ summary: { observed: 2, warned: 0, median_lead_days: null, false_alarms: 3 } }),
    "No community was warned before the radar saw its cut-off; 0 of 2 communities warned; 3 false alarms.",
  );
  assert.equal(
    headline({ summary: { observed: 0, false_alarms: 0 } }),
    "No community was seen cut off by the radar in the hindcast; 0 false alarms.",
  );
});

test("monitored communities are listed by expected cut-off, those never cut off last", () => {
  assert.deepEqual(
    sortedCommunities(REPORT).map((c) => c.community_id),
    ["soon", "late", "never"],
  );
});

test("the chance of being cut off is shown as a percentage, a dash when unknown", () => {
  const soon = REPORT.communities[2];
  assert.equal(isolationChance(soon, 7), "40 %");
  assert.equal(isolationChance(soon, 30), "90 %");
  assert.equal(isolationChance(REPORT.communities[1], 7), "—");
});

test("the panel lists the communities with their names and reports a pick", () => {
  const document = installDom('<div id="panel"></div><div id="limits"></div>');
  const picked = [];

  renderEarlyWarningPanel(document.querySelector("#panel"), REPORT, {
    names: { soon: "Uarini" },
    selectedId: "soon",
    onSelect: (id) => picked.push(id),
  });
  renderLimits(document.querySelector("#limits"), REPORT);

  const rows = [...document.querySelectorAll(".ew-row")];
  assert.equal(rows.length, 3);
  assert.match(rows[0].textContent, /Uarini/);
  assert.match(rows[0].textContent, /7 d 40 %/);
  assert.equal(rows[0].getAttribute("aria-pressed"), "true");
  assert.match(document.querySelector(".ew-headline").textContent, /Median warning: 24 days/);
  rows[1].click();
  assert.deepEqual(picked, ["late"]);
  assert.match(document.querySelector("#limits").textContent, /The river twin\./);
  assert.equal(document.querySelectorAll("#limits li").length, 2);
});

test("the panel never uses the status colours of the main view", () => {
  const document = installDom('<div id="panel"></div>');
  renderEarlyWarningPanel(document.querySelector("#panel"), REPORT);
  assert.doesNotMatch(document.querySelector("#panel").innerHTML, /isolated|at_risk|at-risk|connected/);
});

test("the chart scene has the observed line, the forecast band and median, and the selected community's levels", () => {
  const scene = forecastScene(REPORT, REPORT.communities[2], 600);

  assert.ok(scene.observed.startsWith("M"));
  assert.equal(scene.observed.split("L").length, 2);
  assert.equal(scene.median.split("L").length, 2);
  assert.equal(scene.band.split(" ").length, 4);
  assert.deepEqual(
    scene.thresholds.map((t) => [t.kind, t.cm]),
    [
      ["at-risk", 900],
      ["cut-off", 860],
    ],
  );
  // A lower level sits lower on the chart, inside it
  const [atRisk, cutOff] = scene.thresholds;
  assert.ok(cutOff.y > atRisk.y && cutOff.y < CHART_HEIGHT);
  assert.ok(scene.issued > 0 && scene.issued < 600);
});

test("without a selected community the chart has no threshold lines; with no data it draws nothing", () => {
  const document = installDom('<div id="c"></div>');

  renderLevelForecastChart(document.querySelector("#c"), REPORT);
  assert.ok(document.querySelector("#c svg"));
  assert.equal(document.querySelectorAll("#c .ew-threshold").length, 0);

  renderLevelForecastChart(document.querySelector("#c"), REPORT, { community: REPORT.communities[2] });
  assert.equal(document.querySelectorAll("#c .ew-threshold").length, 2);

  assert.equal(renderLevelForecastChart(document.querySelector("#c"), { communities: [] }), null);
  assert.equal(document.querySelector("#c").innerHTML, "");
});
