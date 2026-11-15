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
  apiRiverLevel,
  installDom,
  installLeaflet,
  waitFor,
} from "./helpers/browser.js";

const PAGE = `
  <h1 class="wordmark">River Lifeline</h1><span data-area-name>Somewhere</span><span data-hub-name></span>
  <span data-gauge-name></span><span data-gauge-source></span>
  <span id="data-mode"></span><div id="clock"></div>
  <button id="theme-toggle" type="button" role="switch" aria-checked="true"></button>
  <div id="headline"></div>
  <div id="map"></div>
  <button id="layers-toggle" aria-controls="layer-controls" aria-expanded="true">Layers</button>
  <div id="layer-controls"></div><p id="image-plate" hidden></p><div id="map-key"></div>
  <span id="list-count"></span><input id="list-search" /><div id="list-filters"></div><div id="community-list"></div>
  <div id="list-download" hidden><a data-format="csv"></a><a data-format="geojson"></a></div>
    <div id="ledger"></div><div id="dossier"></div><span id="record-updated" hidden></span>`;

const API = "./api/v1/aois/tefe";
const OVERVIEW = apiOverview([
  apiFeature({ id: "alvaraes", name: "Alvarães", status: "connected", width: 400 }),
  apiFeature({ id: "uarini", name: "Uarini", status: "isolated", width: null, previousStatus: "at_risk" }),
  apiFeature({ id: "nogueira", name: "Nogueira", status: "isolated", width: null }),
  apiFeature({ id: "caiambe", name: "Caiambé", status: "at_risk", width: 40, reason: "narrow_route" }),
]);

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

const PASSES = [
  apiPassItem("2026-08-28", {
    riverLevelM: 11.8,
    track: 161,
    direction: "ascending",
    counts: { monitored: 4, connected: 3, at_risk: 1, isolated: 0, no_data: 0, changed: 0 },
  }),
  apiPassItem("2026-09-09"),
];

let copies = 0;

/** Starts a fresh app.js with `api` answering each URL (a function of the URL, or throwing for a down API). */
async function startApp(api, { leaflet = true, hash = "" } = {}) {
  const document = installDom(PAGE);
  if (hash) window.location.hash = hash;
  globalThis.location = window.location;
  globalThis.history = window.history;
  const map = leaflet ? installLeaflet() : null;
  if (!leaflet) globalThis.L = undefined;
  const requests = [];
  globalThis.fetch = async (url) => {
    requests.push(url);
    const body = api(url);
    return { ok: body !== undefined, json: async () => body };
  };
  await import(`../src/app.js?copy=${++copies}`);
  await waitFor(() => document.querySelector("#community-list").children.length > 0);
  // The map module is shared by every copy of the app: let this one finish laying its map out, so it can't reach
  // into the next test's map
  if (map) {
    await waitFor(() => map.invalidated);
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  return { document, requests, map };
}

function liveApi(url) {
  if (url === API) return OVERVIEW.aoi;
  if (url === `${API}/summary`) return OVERVIEW.summary;
  if (url === `${API}/passes/latest/communities`) return OVERVIEW.collection;
  if (url === "./api/v1/meta") return apiMeta();
  if (url === `${API}/passes`) return { items: PASSES };
  if (url === `${API}/passes/latest`) return apiPassDetail("2026-09-09");
  if (url === `${API}/river-level`) return apiRiverLevel();
  if (url === `${API}/passes/nisar-2026-08-28/communities`) return EARLIER;
  if (url === `${API}/passes/nisar-2026-08-28`) return apiPassDetail("2026-08-28", ["radar_hh"]);
  if (url === `${API}/passes/nisar-2026-09-09/communities`) return OVERVIEW.collection;
  if (url === `${API}/passes/nisar-2026-09-09`) return apiPassDetail("2026-09-09");
  if (!url.startsWith(`${API}/communities/`)) return undefined;
  const id = url.slice(`${API}/communities/`.length);
  return {
    id,
    name: id,
    kind: "village",
    monitored: true,
    village_location: [-64.6, -3.3],
    history: [apiHistoryEntry("2026-08-28", "at_risk", 40), apiHistoryEntry("2026-09-09", "isolated")],
  };
}

const down = () => {
  throw new TypeError("Failed to fetch");
};
const names = (document) =>
  [...document.querySelectorAll("#community-list .site-main strong")].map((name) => name.textContent);
const onMap = (map) => map.images.filter((image) => !image.removed).map((image) => image.url.split("/").pop());

/** Starts the live app and waits until the map has handed its controls over. */
async function startLive(options) {
  const started = await startApp(liveApi, options);
  await waitFor(() => started.map.images.length > 0);
  return started;
}

test("shows the live monitor: the day's sentence, the communities most in need first, the pass in figures", async () => {
  const { document } = await startApp(liveApi);

  assert.equal(document.querySelector("#data-mode").textContent, "Live record");
  assert.equal(document.querySelector(".headline-count").textContent, "2");
  assert.match(document.querySelector("#headline").textContent, /9 Sep 2026 · 4 communities watched/);
  assert.match(document.querySelector("#headline").textContent, /communities have lost the water route to Tefé\./);
  assert.deepEqual(names(document), ["Nogueira", "Uarini", "Caiambé", "Alvarães"]);
  assert.equal(document.querySelector("#list-count").textContent, "4 of 4");
  assert.match(document.querySelector("#ledger").textContent, /Isolated\s*2\s*\/ 4\s*\+2 since 28 Aug/);
  assert.match(document.querySelector("#image-plate").textContent, /NISAR · L-band HH · Track 97 ↓ descending/);
  assert.match(document.querySelector("#record-updated").textContent, /Record updated 2026-11-15 04:54 UTC/);
});

test("names the area, the hub and the gauge from the API wherever the page says them", async () => {
  const { document } = await startApp(liveApi);

  assert.equal(document.querySelector("[data-area-name]").textContent, "Tefé");
  assert.equal(document.querySelector("[data-gauge-name]").textContent, "Tefé-Missões");
  assert.equal(document.querySelector("[data-gauge-source]").textContent, "ANA, Tefé-Missões");
  assert.equal(document.title, "River Lifeline | Tefé");
  assert.match(document.querySelector("#map-key").textContent, /Tefé, the hub/);
});

test("the day's download points at the backend's export of the pass on screen", async () => {
  const { document } = await startApp(liveApi);

  assert.equal(document.querySelector("#list-download").hidden, false);
  assert.equal(
    document.querySelector('[data-format="csv"]').href,
    "http://localhost/api/v1/aois/tefe/passes/nisar-2026-09-09/export?format=csv",
  );
});

test("nothing is picked at first; picking a community shows its dossier and history and keeps it in the URL", async () => {
  const { document, map, requests } = await startLive();
  assert.match(document.querySelector("#dossier").textContent, /Pick a community/);

  document.querySelectorAll("#community-list button")[3].click();

  assert.equal(document.querySelector(".dossier-name").textContent, "Alvarães");
  assert.match(document.querySelector(".dossier-status").textContent, /Connected on 9 Sep 2026/);
  await waitFor(() => document.querySelectorAll(".history-bar").length === 2);
  assert.ok(requests.includes(`${API}/communities/alvaraes`));
  assert.equal(window.location.hash, "#d=2026-09-09&c=alvaraes");
  assert.equal(document.querySelector("#community-list .is-selected strong").textContent, "Alvarães");
  assert.ok(map.markers.some((marker) => /is-selected/.test(marker.options.icon.className)));
});

test("the clock steps to another pass: its statuses, figures and images, keeping the selection", async () => {
  const { document, map, requests } = await startLive();
  document.querySelectorAll("#community-list button")[0].click();

  document.querySelector('.clock-step[data-step="-1"]').click();
  await waitFor(() => /28 Aug 2026/.test(document.querySelector("#headline").textContent));

  assert.ok(requests.includes(`${API}/passes/nisar-2026-08-28/communities`));
  assert.equal(document.querySelector(".headline-count").textContent, "0");
  assert.equal(document.querySelector(".dossier-name").textContent, "Nogueira");
  assert.match(document.querySelector(".dossier-status").textContent, /At risk on 28 Aug 2026/);
  assert.match(document.querySelector("#ledger").textContent, /first pass on record/);
  // Drawn most in need first: the widest connected route comes last
  assert.equal(map.markers.at(-1).tooltip, "<b>Alvarães</b> · Connected<br>Narrowest point 200–400 m");
  // That pass only serves its radar: the water overlay goes, the radar is the new pass's
  assert.deepEqual(onMap(map), ["radar_hh.png"]);
  assert.match(map.images.at(-1).url, /nisar-2026-08-28/);
  assert.equal(document.querySelector(".clock-date").value, "2026-08-28");
  assert.match(window.location.hash, /d=2026-08-28/);
});

test("the page opens on the latest complete pass and the clock offers only the complete ones", async () => {
  const both = ["radar_hh", "sentinel1_vv", "water"];
  const items = [
    apiPassItem("2026-08-28", { layerIds: both }),
    apiPassItem("2026-09-02", { layerIds: ["radar_hh", "water"] }),
    apiPassItem("2026-09-09", { layerIds: ["radar_hh", "water"] }),
  ];
  const { document } = await startApp((url) => (url === `${API}/passes` ? { items } : liveApi(url)));
  await waitFor(() => /28 Aug 2026/.test(document.querySelector("#headline").textContent));

  const field = document.querySelector(".clock-date");
  assert.deepEqual(
    [...field.options].map((option) => option.value),
    ["2026-08-28"],
  );
  assert.equal(field.value, "2026-08-28");
});

test("a shared link opens its day and its community", async () => {
  const { document } = await startLive({ hash: "#d=2026-08-30&c=uarini" });

  await waitFor(() => document.querySelector(".dossier-name")?.textContent === "Uarini");
  assert.match(document.querySelector("#headline").textContent, /28 Aug 2026 .*latest pass before 30 Aug/);
});

test("play record runs from the first pass to the last, one every 1.3 s, then stops", async (t) => {
  const { document } = await startLive();
  t.mock.timers.enable({ apis: ["setInterval"] });

  document.querySelector(".clock-play").click();
  await waitFor(() => /28 Aug 2026/.test(document.querySelector("#headline").textContent));
  assert.equal(document.querySelector(".clock-play").textContent, "Pause");

  t.mock.timers.tick(1300);
  await waitFor(() => /9 Sep 2026/.test(document.querySelector("#headline").textContent));
  assert.equal(document.querySelector(".clock-play").textContent, "Play record");
});

test("the filters and the search narrow the list and the map together", async () => {
  const { document, map } = await startLive();

  document.querySelector('[data-filter="isolated"]').click();
  assert.deepEqual(names(document), ["Nogueira", "Uarini"]);
  assert.equal(document.querySelector("#list-count").textContent, "2 of 4");

  document.querySelector('[data-filter="changed"]').click();
  assert.deepEqual(names(document), ["Uarini"]);
  assert.match(document.querySelector("#community-list .site-route small").textContent, /was at risk/);

  document.querySelector('[data-filter="all"]').click();
  const search = document.querySelector("#list-search");
  search.value = "alv";
  search.dispatchEvent(new window.Event("input"));
  assert.deepEqual(names(document), ["Alvarães"]);
  assert.ok(map.markers.length >= 4);

  search.value = "zzz";
  search.dispatchEvent(new window.Event("input"));
  assert.match(document.querySelector("#community-list").textContent, /No community matches this filter on 9 Sep\./);
});

test("frames the map on the area and draws the latest pass's images as the catalog says", async () => {
  const { map } = await startLive();

  assert.deepEqual(map.view.bounds, [
    [-3.9, -65.2],
    [-2.9, -64.3],
  ]);
  assert.equal(map.circles.length, 1);
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

test("the layers panel starts folded, so the map shows first, and unfolds and folds", async () => {
  const { document } = await startApp(liveApi);
  const toggle = document.querySelector("#layers-toggle");
  assert.equal(document.querySelector("#layer-controls").hidden, true);
  assert.equal(toggle.getAttribute("aria-expanded"), "false");

  toggle.click();
  assert.equal(document.querySelector("#layer-controls").hidden, false);
  toggle.click();
  assert.equal(document.querySelector("#layer-controls").hidden, true);
});

test("the night switch turns the page and the basemap to day, and back", async () => {
  const { document, map } = await startLive();
  const toggle = document.querySelector("#theme-toggle");
  document.documentElement.dataset.theme = "dark";

  toggle.click();
  assert.equal(document.documentElement.dataset.theme, "light");
  assert.equal(toggle.getAttribute("aria-checked"), "false");
  assert.match(map.tiles[0].url, /\/rastertiles\/voyager\//);

  toggle.click();
  assert.equal(document.documentElement.dataset.theme, "dark");
  assert.match(map.tiles[0].url, /\/dark_all\//);
});

test("falls back to the demo data when the API is unreachable, and its passes still switch", async (t) => {
  t.mock.method(console, "info", () => {});
  const { document, requests } = await startApp(down);

  assert.equal(document.querySelector("#data-mode").textContent, "Demo data");
  assert.equal(document.querySelector("#list-count").textContent, "4 of 4");
  assert.equal(document.querySelector("#list-download").hidden, true);
  assert.match(document.querySelector("#layer-controls").textContent, /Demo data/);
  // The overview's calls, and none for a pass or a history: the demo data answers those
  assert.ok(requests.every((url) => !url.includes("/communities/")));

  document.querySelector('.clock-step[data-step="-1"]').click();
  await waitFor(() => /28 Aug 2026/.test(document.querySelector("#headline").textContent));
});

test("tells the user when the map cannot load", async (t) => {
  t.mock.method(console, "error", () => {});
  const { document } = await startApp(liveApi, { leaflet: false });

  await waitFor(() => document.querySelector("#map .map-error"));
  assert.match(document.querySelector("#map").textContent, /Map could not load/);
});
