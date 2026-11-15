// The early warning section in the whole page: shown only when the API has one, and never touching the main view.
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  apiFeature,
  apiHistoryEntry,
  apiMeta,
  apiOverview,
  apiPassDetail,
  apiPassItem,
  installChart,
  installDom,
  installLeaflet,
  waitFor,
} from "./helpers/browser.js";
import { loadEarlyWarning } from "../src/services/api.js";
import { REPORT } from "./helpers/earlyWarning.js";

const PAGE = `
  <span id="data-mode"></span>
  <strong id="stat-communities">--</strong><strong id="stat-isolated">--</strong><strong id="stat-at-risk">--</strong>
  <strong id="stat-pass">--</strong><span id="stat-river"></span>
  <div id="map"></div><div id="community-list"></div><div id="selected-summary"></div>
  <canvas id="route-chart"></canvas><div id="status-content"></div>
  <div id="layer-controls"></div><div id="status-filter"></div>
  <section id="early-warning" hidden>
    <button id="early-warning-toggle" aria-controls="early-warning-body" aria-expanded="false"></button>
    <div id="early-warning-body" hidden>
      <div id="early-warning-content"></div><canvas id="level-forecast-chart"></canvas>
      <div id="early-warning-limits"></div>
    </div>
  </section>`;

const API = "./api/v1/aois/tefe";
const OVERVIEW = apiOverview([
  apiFeature({ id: "soon", name: "Uarini", status: "isolated" }),
  apiFeature({ id: "late", name: "Nogueira", status: "connected" }),
]);

let copies = 0;

async function startApp(earlyWarning) {
  const document = installDom(PAGE);
  const charts = installChart();
  installLeaflet();
  globalThis.fetch = async (url) => {
    let body;
    if (url === `${API}/early-warning`) body = earlyWarning;
    else if (url === API) body = OVERVIEW.aoi;
    else if (url === `${API}/passes/latest/communities`) body = OVERVIEW.collection;
    else if (url === "./api/v1/meta") body = apiMeta();
    else if (url === `${API}/summary`) body = OVERVIEW.summary;
    else if (url === `${API}/passes`) body = { items: [apiPassItem("2026-09-09")] };
    else if (url === `${API}/passes/latest`) body = apiPassDetail("2026-09-09");
    else if (url.startsWith(`${API}/communities/`))
      body = {
        id: "x",
        name: "x",
        kind: "village",
        village_location: [-64.6, -3.3],
        history: [apiHistoryEntry("2026-09-09", "isolated")],
      };
    return { ok: body !== undefined, status: body === undefined ? 404 : 200, json: async () => body };
  };
  await import(`../src/app.js?ew=${++copies}`);
  await waitFor(() => document.querySelector("#community-list").children.length > 0);
  await new Promise((resolve) => setTimeout(resolve, 20));
  return { document, charts };
}

test("on 404 the section stays hidden and the main view is as before", async () => {
  const { document } = await startApp(undefined);

  assert.equal(document.querySelector("#early-warning").hidden, true);
  assert.equal(document.querySelector("#early-warning-content").innerHTML, "");
  assert.equal(document.querySelector("#stat-isolated").textContent, "01");
  assert.equal(document.querySelector("#stat-communities").textContent, "02");
});

test("with an early warning the section shows, collapsed, without changing the main view's figures", async () => {
  const { document, charts } = await startApp(REPORT);
  await waitFor(() => !document.querySelector("#early-warning").hidden);

  assert.equal(document.querySelector("#early-warning-body").hidden, true);
  assert.match(document.querySelector("#early-warning-content").textContent, /Uarini/);
  assert.equal(document.querySelector("#stat-isolated").textContent, "01");
  assert.equal(document.querySelector("#stat-at-risk").textContent, "00");
  const forecastCharts = () => charts.filter((chart) => chart.canvas.id === "level-forecast-chart");
  assert.equal(forecastCharts().length, 0); // drawn only once opened

  document.querySelector("#early-warning-toggle").click();

  assert.equal(document.querySelector("#early-warning-body").hidden, false);
  assert.equal(forecastCharts().length, 1);
  assert.equal(forecastCharts()[0].config.data.datasets.at(-1).label, "Cut off below");
});

test("loadEarlyWarning is null on 404, on a failed request and on a document that is not experimental", async () => {
  globalThis.fetch = async () => ({ ok: false, status: 404, json: async () => ({}) });
  assert.equal(await loadEarlyWarning("tefe"), null);
  globalThis.fetch = async () => {
    throw new Error("down");
  };
  assert.equal(await loadEarlyWarning("tefe"), null);
  globalThis.fetch = async () => ({ ok: true, json: async () => ({ ...REPORT, experimental: false }) });
  assert.equal(await loadEarlyWarning("tefe"), null);
  globalThis.fetch = async (url) => ({ ok: url === "./api/v1/aois/tefe/early-warning", json: async () => REPORT });
  assert.equal((await loadEarlyWarning("tefe")).aoi_id, "tefe");
});
