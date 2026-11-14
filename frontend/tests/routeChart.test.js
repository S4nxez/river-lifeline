import { test } from "node:test";
import assert from "node:assert/strict";
import { assessment, installChart, installDom } from "./helpers/browser.js";
import { CHART_COLORS, plottedWidth, renderRouteChart } from "../src/components/routeChart.js";

const charts = installChart();
const canvas = installDom('<canvas id="chart"></canvas>').querySelector("#chart");

test("isolation plots as zero width and a pass without data as a gap", () => {
  assert.equal(plottedWidth(assessment("2026-08-04", "connected", 200)), 200);
  assert.equal(plottedWidth(assessment("2026-08-28", "isolated")), 0);
  assert.equal(plottedWidth(assessment("2026-09-09", "no_data")), null);
});

test("plots the route width of every pass against the connected threshold", () => {
  renderRouteChart(canvas, {
    assessments: [
      assessment("2026-08-04", "connected", 200),
      assessment("2026-08-16", "at_risk", 40),
      assessment("2026-08-28", "isolated"),
    ],
  });

  const { canvas: target, config } = charts.at(-1);
  const [widths, threshold] = config.data.datasets;
  assert.equal(target, canvas);
  assert.deepEqual(config.data.labels, ["4 Aug 2026", "16 Aug 2026", "28 Aug 2026"]);
  assert.deepEqual(widths.data, [200, 40, 0]);
  assert.deepEqual(widths.pointBackgroundColor, ["#0b7a52", "#a15c07", "#b42318"]);
  assert.deepEqual(threshold.data, [60, 60, 60]);
});

test("a missing history draws an empty chart", () => {
  renderRouteChart(canvas, null);

  assert.deepEqual(charts.at(-1).config.data.labels, []);
});

test("replaces the previous chart instead of stacking a new one on it", () => {
  renderRouteChart(canvas, { assessments: [] });
  const previous = charts.at(-1);

  renderRouteChart(canvas, { assessments: [] });

  assert.equal(previous.destroyed, true);
  assert.equal(charts.at(-1).destroyed, false);
});

test("draws in the light palette by default and in the dark one for the dark theme", () => {
  renderRouteChart(canvas, { assessments: [] });
  assert.equal(charts.at(-1).config.data.datasets[0].borderColor, CHART_COLORS.light.line);

  renderRouteChart(canvas, { assessments: [] }, "dark");
  const { data, options } = charts.at(-1).config;
  assert.equal(data.datasets[0].borderColor, CHART_COLORS.dark.line);
  assert.equal(data.datasets[1].borderColor, CHART_COLORS.dark.threshold);
  assert.equal(options.scales.y.grid.color, CHART_COLORS.dark.grid);
  assert.equal(options.plugins.tooltip.backgroundColor, CHART_COLORS.dark.tooltip.background);
});
