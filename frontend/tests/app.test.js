// app.js wires everything up as soon as it is imported, so each test sets the page up and imports a fresh copy.
import { test } from "node:test";
import assert from "node:assert/strict";
import { assessment, community, installChart, installDom, installLeaflet, waitFor } from "./helpers/browser.js";

const PAGE = `
  <span id="data-mode"></span>
  <strong id="stat-communities">--</strong><strong id="stat-isolated">--</strong><strong id="stat-at-risk">--</strong>
  <strong id="stat-pass">--</strong><span id="stat-river"></span>
  <div id="map"></div><div id="community-list"></div><div id="selected-summary"></div>
  <canvas id="route-chart"></canvas><div id="status-content"></div>`;

const OVERVIEW = {
  hub: { id: "tefe", name: "Tefé", kind: "hub", lat: -3.354, lng: -64.711 },
  latestPass: { id: "p4", mission: "NISAR", acquiredOn: "2026-09-09", riverLevelCm: 980, counts: {} },
  communities: [
    community({ id: "alvaraes", name: "Alvarães", latest: { status: "connected" } }),
    community({ id: "uarini", name: "Uarini", latest: { status: "isolated" } }),
    community({ id: "nogueira", name: "Nogueira", latest: { status: "isolated" } }),
    community({ id: "caiambe", name: "Caiambé", latest: { status: "at_risk" } }),
  ],
};

let copies = 0;

/** Starts a fresh app.js with `api` answering each URL (a function of the URL, or throwing for a down API). */
async function startApp(api, { leaflet = true } = {}) {
  const document = installDom(PAGE);
  const charts = installChart();
  if (leaflet) installLeaflet();
  else globalThis.L = undefined;
  const requests = [];
  globalThis.fetch = async (url) => {
    requests.push(url);
    const body = api(url);
    return { ok: body !== undefined, json: async () => body };
  };
  await import(`../src/app.js?copy=${++copies}`);
  await waitFor(() => document.querySelector("#community-list").children.length > 0 && charts.length > 0);
  return { document, charts, requests };
}

const liveApi = (url) =>
  url === "./api/communities"
    ? OVERVIEW
    : { assessments: [assessment("2026-08-28", "at_risk", 40), assessment("2026-09-09", "isolated")] };

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
  assert.ok(requests.includes("./api/communities/nogueira"));
  assert.deepEqual(charts.at(-1).config.data.datasets[0].data, [40, 0]);
});

test("selecting another community shows it and loads its history", async () => {
  const { document, requests } = await startApp(liveApi);

  document.querySelectorAll("#community-list button")[3].click();

  assert.equal(document.querySelector("#selected-summary h3").textContent, "Alvarães");
  assert.equal(document.querySelector("#status-content h3").textContent, "Water route open");
  await waitFor(() => requests.includes("./api/communities/alvaraes"));
});

test("falls back to the demo data when the API is unreachable", async (t) => {
  t.mock.method(console, "info", () => {});
  const { document, requests } = await startApp(() => {
    throw new TypeError("Failed to fetch");
  });

  assert.equal(document.querySelector("#data-mode").textContent, "DEMO DATA");
  assert.equal(document.querySelector("#stat-communities").textContent, "04");
  assert.deepEqual(requests, ["./api/communities"]);
});

test("tells the user when the map cannot load", async (t) => {
  t.mock.method(console, "error", () => {});
  const { document } = await startApp(liveApi, { leaflet: false });

  await waitFor(() => document.querySelector("#map .map-error"));
  assert.match(document.querySelector("#map").textContent, /Map could not load/);
});
