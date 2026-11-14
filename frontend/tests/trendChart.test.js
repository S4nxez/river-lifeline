import { test } from "node:test";
import assert from "node:assert/strict";
import { installChart, installDom, site } from "./helpers/browser.js";
import { renderTrendChart } from "../src/components/trendChart.js";

const charts = installChart();
const canvas = installDom('<canvas id="chart"></canvas>').querySelector("#chart");

test("plots the VV and VH series over the observation dates", () => {
  const dates = ["Aug 20", "Aug 25"];
  renderTrendChart(canvas, site({ vv: [-8.8, -8.6], vh: [-15.4, -15.6] }), dates);

  const { canvas: target, config } = charts.at(-1);
  assert.equal(target, canvas);
  assert.equal(config.type, "line");
  assert.deepEqual(config.data.labels, dates);
  assert.deepEqual(
    config.data.datasets.map(({ label, data }) => ({ label, data })),
    [
      { label: "VV", data: [-8.8, -8.6] },
      { label: "VH", data: [-15.4, -15.6] },
    ],
  );
});

test("replaces the previous chart instead of stacking a new one on it", () => {
  renderTrendChart(canvas, site(), ["a"]);
  const previous = charts.at(-1);

  renderTrendChart(canvas, site(), ["a"]);

  assert.equal(previous.destroyed, true);
  assert.equal(charts.at(-1).destroyed, false);
});
