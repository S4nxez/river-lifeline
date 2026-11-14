// app.js wires everything up as soon as it is imported, so each test sets the page up and imports a fresh copy.
import { test } from "node:test";
import assert from "node:assert/strict";
import { installChart, installDom, installLeaflet, site, waitFor } from "./helpers/browser.js";
import { sites as demoSites } from "../src/data/sites.js";

const PAGE = `
  <strong id="stat-areas">--</strong><strong id="stat-signals">--</strong>
  <div id="map"></div><div id="site-list"></div><div id="selected-summary"></div>
  <canvas id="trend-chart"></canvas><div id="insight-content"></div>`;

let copies = 0;

async function startApp(fetchImpl, { leaflet = true } = {}) {
  const document = installDom(PAGE);
  installChart();
  if (leaflet) installLeaflet();
  else globalThis.L = undefined;
  globalThis.fetch = fetchImpl;
  await import(`../src/app.js?copy=${++copies}`);
  await waitFor(() => document.querySelector("#site-list").children.length > 0);
  return document;
}

const apiSites = [
  site({ id: "a", name: "Site A", level: "high" }),
  site({ id: "b", name: "Site B", level: "medium" }),
  site({ id: "c", name: "Site C", level: "low" }),
];

test("shows the catalog the API serves", async () => {
  const requests = [];
  const document = await startApp(async (url) => {
    requests.push(url);
    return { ok: true, json: async () => ({ observationDates: ["d1", "d2"], sites: apiSites }) };
  });

  assert.deepEqual(requests, ["./api/sites"]);
  assert.equal(document.querySelector("#stat-areas").textContent, "03");
  // 5 per high, 3 per medium and 1 per low change
  assert.equal(document.querySelector("#stat-signals").textContent, "09");
  assert.equal(document.querySelectorAll("#site-list button").length, 3);
  assert.equal(document.querySelector("#selected-summary h3").textContent, "Site A");
});

test("falls back to the demo data when the API is unreachable", async (t) => {
  t.mock.method(console, "info", () => {});
  const document = await startApp(async () => {
    throw new TypeError("Failed to fetch");
  });

  assert.equal(document.querySelector("#stat-areas").textContent, String(demoSites.length).padStart(2, "0"));
  assert.equal(document.querySelector("#selected-summary h3").textContent, demoSites[0].name);
});

test("falls back to the demo data when the API has no sites", async () => {
  const document = await startApp(async () => ({ ok: true, json: async () => ({ sites: [] }) }));

  assert.equal(document.querySelectorAll("#site-list button").length, demoSites.length);
});

test("selecting a site in the list shows it in the summary", async () => {
  const document = await startApp(async () => ({ ok: true, json: async () => ({ sites: apiSites }) }));

  document.querySelectorAll("#site-list button")[2].click();

  assert.equal(document.querySelector("#selected-summary h3").textContent, "Site C");
});

test("tells the user when the map cannot load", async (t) => {
  t.mock.method(console, "error", () => {});
  const document = await startApp(async () => ({ ok: true, json: async () => ({ sites: apiSites }) }), {
    leaflet: false,
  });

  await waitFor(() => document.querySelector("#map .map-error"));
  assert.match(document.querySelector("#map").textContent, /Map could not load/);
});
