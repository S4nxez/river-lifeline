// app.js wires everything up as soon as it is imported, so each test sets the page up and imports a fresh copy.
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

const PAGE = `
  <span id="data-mode"></span>
  <strong id="stat-communities">--</strong><strong id="stat-isolated">--</strong><strong id="stat-at-risk">--</strong>
  <strong id="stat-pass">--</strong><span id="stat-river"></span>
  <div id="map"></div><div id="community-list"></div><div id="selected-summary"></div>
  <canvas id="route-chart"></canvas><div id="status-content"></div>
  <button id="theme-toggle" type="button" role="switch" aria-checked="false"></button>
  <h1>Who can still reach <span data-area-name>Somewhere</span>?</h1>
  <button id="layers-toggle" aria-controls="layer-controls" aria-expanded="true">Layers</button>
  <div id="layer-controls"></div><div id="status-filter"></div>`;

const API = "./api/v1/aois/tefe";
const OVERVIEW = apiOverview([
  apiFeature({ id: "alvaraes", name: "Alvarães", status: "connected" }),
  apiFeature({ id: "uarini", name: "Uarini", status: "isolated" }),
  apiFeature({ id: "nogueira", name: "Nogueira", status: "isolated" }),
  apiFeature({ id: "caiambe", name: "Caiambé", status: "at_risk" }),
]);

let copies = 0;

/** Starts a fresh app.js with `api` answering each URL (a function of the URL, or throwing for a down API). */
async function startApp(api, { leaflet = true } = {}) {
  const document = installDom(PAGE);
  const charts = installChart();
  const map = leaflet ? installLeaflet() : null;
  if (!leaflet) globalThis.L = undefined;
  const requests = [];
  globalThis.fetch = async (url) => {
    requests.push(url);
    const body = api(url);
    return { ok: body !== undefined, json: async () => body };
  };
  await import(`../src/app.js?copy=${++copies}`);
  await waitFor(() => document.querySelector("#community-list").children.length > 0 && charts.length > 0);
  // The map module is shared by every copy of the app: let this one finish laying its map out, so it can't reach
  // into the next test's map
  if (map) {
    await waitFor(() => map.invalidated);
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  return { document, charts, requests, map };
}

// An older pass, with its own statuses and only its radar image
const EARLIER = apiOverview(
  [
    apiFeature({ id: "alvaraes", name: "Alvarães", status: "connected", width: 200 }),
    apiFeature({ id: "uarini", name: "Uarini", status: "connected", width: 100 }),
    apiFeature({ id: "nogueira", name: "Nogueira", status: "at_risk", width: 40 }),
    apiFeature({ id: "caiambe", name: "Caiambé", status: "connected", width: 100 }),
  ],
  "2026-08-28",
).collection;

function liveApi(url) {
  if (url === API) return OVERVIEW.aoi;
  if (url === `${API}/summary`) return OVERVIEW.summary;
  if (url === `${API}/passes/latest/communities`) return OVERVIEW.collection;
  if (url === "./api/v1/meta") return apiMeta();
  if (url === `${API}/passes`)
    return { items: [apiPassItem("2026-08-28", { riverLevelM: 11.8 }), apiPassItem("2026-09-09")] };
  if (url === `${API}/passes/latest`) return apiPassDetail("2026-09-09");
  if (url === `${API}/passes/nisar-2026-08-28/communities`) return EARLIER;
  if (url === `${API}/passes/nisar-2026-08-28`) return apiPassDetail("2026-08-28", ["radar_hh"]);
  const id = url.slice(`${API}/communities/`.length);
  return {
    id,
    name: id,
    kind: "village",
    village_location: [-64.6, -3.3],
    history: [apiHistoryEntry("2026-08-28", "at_risk", 40), apiHistoryEntry("2026-09-09", "isolated")],
  };
}

test("shows the live monitor: counts, latest pass and the communities most in need first", async () => {
  const { document } = await startApp(liveApi);

  assert.equal(document.querySelector("#data-mode").textContent, "LIVE DATA");
  assert.equal(document.querySelector("#stat-communities").textContent, "04");
  assert.equal(document.querySelector("#stat-isolated").textContent, "02");
  assert.equal(document.querySelector("#stat-at-risk").textContent, "01");
  assert.equal(document.querySelector("#stat-pass").textContent, "9 Sep 2026");
  assert.equal(document.querySelector("#stat-river").textContent, "River at 9.8 m");
  assert.deepEqual(
    [...document.querySelectorAll("#community-list .site-main strong")].map((name) => name.textContent),
    ["Nogueira", "Uarini", "Caiambé", "Alvarães"],
  );
});

test("selects the community most in need and charts its history", async () => {
  const { document, charts, requests } = await startApp(liveApi);

  assert.equal(document.querySelector("#selected-summary h3").textContent, "Nogueira");
  assert.ok(requests.includes(`${API}/communities/nogueira`));
  assert.deepEqual(charts.at(-1).config.data.datasets[0].data, [40, 0]);
});

test("selecting another community shows it and loads its history", async () => {
  const { document, requests } = await startApp(liveApi);

  document.querySelectorAll("#community-list button")[3].click();

  assert.equal(document.querySelector("#selected-summary h3").textContent, "Alvarães");
  assert.equal(document.querySelector("#status-content h3").textContent, "Water route open");
  await waitFor(() => requests.includes(`${API}/communities/alvaraes`));
});

test("falls back to the demo data when the API is unreachable", async (t) => {
  t.mock.method(console, "info", () => {});
  const { document, requests } = await startApp(() => {
    throw new TypeError("Failed to fetch");
  });

  assert.equal(document.querySelector("#data-mode").textContent, "DEMO DATA");
  assert.equal(document.querySelector("#stat-communities").textContent, "04");
  // The overview's calls, and none for a pass or a history: the demo data answers those
  assert.ok(requests.every((url) => url.startsWith("./api/v1/meta") || !url.includes("/communities/")));
  assert.ok(requests.includes(`${API}/passes/latest/communities`));
});

test("tells the user when the map cannot load", async (t) => {
  t.mock.method(console, "error", () => {});
  const { document } = await startApp(liveApi, { leaflet: false });

  await waitFor(() => document.querySelector("#map .map-error"));
  assert.match(document.querySelector("#map").textContent, /Map could not load/);
});

test("the dark mode switch turns the page, the basemap and the chart dark, and back", async () => {
  const { document, charts, map } = await startApp(liveApi);
  await waitFor(() => map.tiles.length > 0 && charts.at(-1).config.data.datasets[0].data.length > 0);
  const toggle = document.querySelector("#theme-toggle");
  await new Promise((resolve) => setTimeout(resolve, 120)); // the map hands its controls over once laid out

  toggle.click();

  assert.equal(document.documentElement.dataset.theme, "dark");
  assert.equal(toggle.getAttribute("aria-checked"), "true");
  assert.match(map.tiles[0].url, /\/dark_all\//);
  const chart = charts.at(-1).config;
  assert.equal(chart.data.datasets[0].borderColor, "#ececec");
  assert.deepEqual(chart.data.datasets[0].data, [40, 0]);

  toggle.click();

  assert.equal(document.documentElement.dataset.theme, "light");
  assert.equal(toggle.getAttribute("aria-checked"), "false");
  assert.match(map.tiles[0].url, /\/rastertiles\/voyager\//);
  assert.equal(charts.at(-1).config.data.datasets[0].borderColor, "#111214");
});

/** Starts the live app and waits until the map has handed its controls over. */
async function startLive() {
  const started = await startApp(liveApi);
  await waitFor(() => started.map.images.length > 0);
  return started;
}

const onMap = (map) => map.images.filter((image) => !image.removed).map((image) => image.url.split("/").pop());

test("names the area from the API wherever the page says it", async () => {
  const { document } = await startApp(liveApi);

  assert.equal(document.querySelector("[data-area-name]").textContent, "Tefé");
  assert.equal(document.title, "River Lifeline | Tefé");
});

test("frames the map on the area and draws the latest pass's images as the catalog says", async () => {
  const { map } = await startLive();

  assert.deepEqual(map.view.bounds, [
    [-3.9, -65.2],
    [-2.9, -64.3],
  ]);
  // radar_hh and water show by default, change doesn't
  assert.deepEqual(onMap(map), ["radar_hh.png", "water.png"]);
});

test("the radar can be turned off and its opacity changed, the overlays stay", async () => {
  const { document, map } = await startLive();

  const slider = document.querySelector("[data-opacity]");
  slider.value = "40";
  slider.dispatchEvent(new window.Event("input"));
  assert.equal(map.images[0].opacity, 0.4);

  document.querySelector('[data-radar=""]').click();
  assert.deepEqual(onMap(map), ["water.png"]);
});

test("an overlay can be turned on from the layers panel", async () => {
  const { document, map } = await startLive();

  const change = document.querySelector('[data-overlay="change"]');
  change.checked = true;
  change.dispatchEvent(new window.Event("change"));

  assert.deepEqual(onMap(map), ["radar_hh.png", "water.png", "change.png"]);
});

test("an image the API fails to serve is flagged in the panel", async () => {
  const { document, map } = await startLive();

  map.images.find((image) => image.url.endsWith("water.png")).handlers.error();

  assert.match(document.querySelector('[data-overlay="water"]').parentElement.textContent, /unavailable/);
});

test("another pass brings its statuses, figures and images, keeping the selection", async () => {
  const { document, map, requests } = await startLive();
  const select = document.querySelector("[data-pass]");

  select.value = "nisar-2026-08-28";
  select.dispatchEvent(new window.Event("change"));
  await waitFor(() => document.querySelector("#stat-pass").textContent === "28 Aug 2026");

  assert.ok(requests.includes(`${API}/passes/nisar-2026-08-28/communities`));
  assert.equal(document.querySelector("#stat-isolated").textContent, "00");
  assert.equal(document.querySelector("#stat-at-risk").textContent, "01");
  assert.equal(document.querySelector("#stat-river").textContent, "River at 11.8 m");
  assert.equal(document.querySelector("#selected-summary h3").textContent, "Nogueira");
  assert.equal(document.querySelector("#status-content h3").textContent, "Route narrowing");
  assert.equal(map.markers.at(-1).tooltip, "Uarini · Connected");
  // That pass only serves its radar: the water overlay goes, the radar is the new pass's
  assert.deepEqual(onMap(map), ["radar_hh.png"]);
  assert.match(map.images.at(-1).url, /nisar-2026-08-28/);
});

test("the status filter hides the other statuses from the list and the map", async () => {
  const { document, map } = await startLive();

  document.querySelector('.filter-chip[data-status="connected"]').click();
  document.querySelector('.filter-chip[data-status="at_risk"]').click();

  assert.deepEqual(
    [...document.querySelectorAll("#community-list .site-main strong")].map((name) => name.textContent),
    ["Nogueira", "Uarini"],
  );
  assert.equal(document.querySelector('.filter-chip[data-status="connected"]').getAttribute("aria-pressed"), "false");
  assert.ok(map.markers.length >= 4);
});

test("the explanation uses the area's connected width from the API", async () => {
  const { document } = await startApp(liveApi);

  document.querySelectorAll("#community-list button")[3].click();

  assert.match(document.querySelector("#status-content .insight-copy").textContent, /at least 60 m wide/);
});

test("the layers panel folds and unfolds", async () => {
  const { document } = await startApp(liveApi);
  const toggle = document.querySelector("#layers-toggle");

  toggle.click();
  assert.equal(document.querySelector("#layer-controls").hidden, true);
  assert.equal(toggle.getAttribute("aria-expanded"), "false");
  toggle.click();
  assert.equal(document.querySelector("#layer-controls").hidden, false);
});

test("in demo mode the panel says there is no imagery and the passes still switch", async (t) => {
  t.mock.method(console, "info", () => {});
  const { document } = await startApp(() => {
    throw new TypeError("Failed to fetch");
  });

  assert.match(document.querySelector("#layer-controls").textContent, /Demo data/);
  const select = document.querySelector("[data-pass]");
  select.value = "nisar-2026-08-04";
  select.dispatchEvent(new window.Event("change"));
  await waitFor(() => document.querySelector("#stat-isolated").textContent === "00");
  assert.equal(document.querySelector("#stat-pass").textContent, "4 Aug 2026");
});
