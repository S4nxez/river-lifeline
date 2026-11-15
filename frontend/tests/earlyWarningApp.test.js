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
  apiRiverLevel,
  installDom,
  installLeaflet,
  waitFor,
} from "./helpers/browser.js";
import { loadEarlyWarning } from "../src/services/api.js";
import { REPORT } from "./helpers/earlyWarning.js";

const PAGE = `
  <span data-area-name></span><span data-hub-name></span><span data-gauge-name></span><span data-gauge-source></span>
  <span id="data-mode"></span><div id="clock"></div>
  <button id="theme-toggle" type="button" role="switch" aria-checked="true"></button>
  <div id="headline"></div><div id="map"></div>
  <button id="layers-toggle" aria-controls="layer-controls" aria-expanded="true">Layers</button>
  <div id="layer-controls"></div><p id="image-plate" hidden></p><div id="map-key"></div>
  <span id="list-count"></span><input id="list-search" /><div id="list-filters"></div><div id="community-list"></div>
  <div id="list-download" hidden><a data-format="csv"></a><a data-format="geojson"></a></div>
  <p id="next-pass" hidden><b id="next-pass-value"></b></p><div id="pass-log"></div>
  <div id="ledger"></div><div id="dossier"></div><span id="record-updated" hidden></span>
  <section id="early-warning" hidden>
    <div id="early-warning-content"></div><div id="level-forecast-chart"></div><div id="early-warning-limits"></div>
  </section>`;

const API = "./api/v1/aois/tefe";
const OVERVIEW = apiOverview([
  apiFeature({ id: "soon", name: "Uarini", status: "isolated", width: null }),
  apiFeature({ id: "late", name: "Nogueira", status: "connected", width: 400 }),
]);

let copies = 0;

async function startApp(earlyWarning) {
  const document = installDom(PAGE);
  globalThis.location = window.location;
  globalThis.history = window.history;
  const map = installLeaflet();
  globalThis.fetch = async (url) => {
    let body;
    if (url === `${API}/early-warning`) body = earlyWarning;
    else if (url === API) body = OVERVIEW.aoi;
    else if (url === `${API}/summary`) body = OVERVIEW.summary;
    else if (url === `${API}/passes/latest/communities`) body = OVERVIEW.collection;
    else if (url === "./api/v1/meta") body = apiMeta();
    else if (url === `${API}/passes`) body = { items: [apiPassItem("2026-09-09")] };
    else if (url === `${API}/passes/latest`) body = apiPassDetail("2026-09-09");
    else if (url === `${API}/river-level`) body = apiRiverLevel();
    else if (url.startsWith(`${API}/communities/`))
      body = {
        id: "soon",
        name: "Uarini",
        kind: "village",
        monitored: true,
        village_location: [-64.6, -3.3],
        history: [apiHistoryEntry("2026-09-09", "isolated")],
      };
    return { ok: body !== undefined, status: body === undefined ? 404 : 200, json: async () => body };
  };
  await import(`../src/app.js?ew=${++copies}`);
  await waitFor(() => document.querySelector("#community-list").children.length > 0);
  await waitFor(() => map.invalidated);
  await new Promise((resolve) => setTimeout(resolve, 20));
  return { document };
}

test("on 404 the section stays hidden", async () => {
  const { document } = await startApp(undefined);

  assert.equal(document.querySelector("#early-warning").hidden, true);
  assert.equal(document.querySelector("#early-warning-content").innerHTML, "");
});

test("with an early warning the section shows, open, and the main view says exactly the same", async () => {
  const without = await startApp(undefined);
  const ledger = without.document.querySelector("#ledger").textContent;
  const headline = without.document.querySelector("#headline").textContent;

  const { document } = await startApp(REPORT);
  await waitFor(() => !document.querySelector("#early-warning").hidden);

  assert.match(document.querySelector("#early-warning-content").textContent, /Uarini/);
  assert.ok(document.querySelector("#level-forecast-chart svg"), "the chart is drawn without a click");
  assert.equal(document.querySelectorAll("#early-warning-limits li").length, 2);
  assert.equal(document.querySelector("#ledger").textContent, ledger);
  assert.equal(document.querySelector("#headline").textContent, headline);
});

test("picking a community in the section draws its thresholds", async () => {
  const { document } = await startApp(REPORT);
  await waitFor(() => !document.querySelector("#early-warning").hidden);

  document.querySelector('[data-community="late"]').click();

  assert.equal(document.querySelector('[data-community="late"]').getAttribute("aria-pressed"), "true");
  assert.equal(document.querySelectorAll("#level-forecast-chart .ew-threshold").length, 1);
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
