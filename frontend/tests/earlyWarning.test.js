import { test } from "node:test";
import assert from "node:assert/strict";
import { installChart, installDom } from "./helpers/browser.js";
import {
  headline,
  isolationChance,
  renderEarlyWarningPanel,
  renderLimits,
  sortedCommunities,
} from "../src/components/earlyWarningPanel.js";
import { FORECAST_COLORS, forecastDatasets, renderLevelForecastChart } from "../src/components/levelForecastChart.js";
import { STATUSES } from "../src/status.js";
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

test("the chance of isolation is shown as a percentage, a dash when unknown", () => {
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
  assert.match(rows[0].textContent, /7 d · 40 %/);
  assert.ok(rows[0].classList.contains("is-selected"));
  assert.match(document.querySelector(".ew-headline").textContent, /Median warning: 24 days/);
  rows[1].click();
  assert.deepEqual(picked, ["late"]);
  assert.equal(document.querySelectorAll("#limits li").length, 2);
});

test("probabilities never use the status colours of the main view", () => {
  const statusColours = Object.values(STATUSES).map((status) => status.color?.toLowerCase());
  for (const palette of Object.values(FORECAST_COLORS)) {
    for (const colour of Object.values(palette)) assert.ok(!statusColours.includes(colour.toLowerCase()), colour);
  }
});

test("the chart draws the observed level, the forecast band and median, and the selected community's levels", () => {
  const { labels, datasets } = forecastDatasets(REPORT, REPORT.communities[2]);

  assert.deepEqual(labels, ["2026-08-31", "2026-09-02", "2026-09-03"]);
  assert.deepEqual(
    datasets.map((d) => d.label),
    ["Observed level", "Forecast 10 %", "Forecast 10-90 %", "Forecast median", "At risk below", "Cut off below"],
  );
  assert.deepEqual(datasets[0].data, [900, 885, null]);
  assert.deepEqual(datasets[3].data, [null, 880, 875]);
  assert.equal(datasets[2].fill, "-1");
  assert.deepEqual(datasets[5].data, [860, 860, 860]);
});

test("without a selected community the chart has no threshold lines, and it is redrawn on each call", () => {
  installDom('<canvas id="c"></canvas>');
  const charts = installChart();

  renderLevelForecastChart(document.querySelector("#c"), REPORT, { theme: "dark" });
  renderLevelForecastChart(document.querySelector("#c"), REPORT, { community: REPORT.communities[2] });

  assert.equal(charts.length, 2);
  assert.ok(charts[0].destroyed);
  assert.equal(charts[0].config.data.datasets.length, 4);
  assert.equal(charts[1].config.data.datasets.length, 6);
});
