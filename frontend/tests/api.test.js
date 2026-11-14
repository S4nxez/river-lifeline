import { test } from "node:test";
import assert from "node:assert/strict";
import { apiFeature, apiHistoryEntry, apiOverview } from "./helpers/browser.js";
import { aoiFromUrl, loadHistory, loadOverview } from "../src/services/api.js";

const BASE = "./api/v1/aois/tefe";

/** Answers each URL from `routes`; an unknown URL is a 404. */
function respond(routes) {
  const requests = [];
  globalThis.fetch = async (url) => {
    requests.push(url);
    const body = routes[url];
    return { ok: body !== undefined, status: body === undefined ? 404 : 200, json: async () => body };
  };
  return requests;
}

function overviewRoutes(features) {
  const { aoi, summary, collection } = apiOverview(features);
  return { [BASE]: aoi, [`${BASE}/summary`]: summary, [`${BASE}/passes/latest/communities`]: collection };
}

test("the overview is built from the AOI, its summary and the latest pass's communities", async () => {
  const caiambe = apiFeature({ id: "caiambe", name: "Caiambé", status: "isolated", width: null });
  const requests = respond(overviewRoutes([caiambe]));

  const { overview, live } = await loadOverview("tefe");

  assert.equal(live, true);
  assert.deepEqual(overview.hub, { id: "tefe", name: "Tefé", kind: "hub", lat: -3.354, lng: -64.711 });
  assert.equal(overview.latestPass.acquiredOn, "2026-09-09");
  assert.equal(overview.latestPass.riverLevelCm, 980);
  assert.deepEqual(overview.latestPass.counts, { connected: 1, atRisk: 1, isolated: 2, noData: 0 });
  assert.deepEqual(overview.communities, [
    {
      id: "caiambe",
      name: "Caiambé",
      kind: "town",
      lat: -2.99,
      lng: -65.113,
      latest: {
        passId: "nisar-2026-09-09",
        acquiredOn: "2026-09-09",
        status: "isolated",
        routeWidthM: null,
        sentinel1: null,
      },
    },
  ]);
  assert.deepEqual(requests.sort(), [BASE, `${BASE}/passes/latest/communities`, `${BASE}/summary`]);
});

test("the overview falls back to the demo data when the API is down, empty or failing", async (t) => {
  t.mock.method(console, "info", () => {});
  globalThis.fetch = async () => {
    throw new TypeError("Failed to fetch");
  };
  const down = await loadOverview("tefe");
  respond(overviewRoutes([]));
  const empty = await loadOverview("tefe");
  respond({});
  const failing = await loadOverview("tefe");

  for (const result of [down, empty, failing]) {
    assert.equal(result.live, false);
    assert.equal(result.overview.hub.name, "Tefé");
  }
});

test("a live history comes from the community card, with the ids escaped", async () => {
  const requests = respond({
    "./api/v1/aois/tefe/communities/a%20b": {
      id: "a b",
      name: "A B",
      kind: "village",
      village_location: [-64.6, -3.3],
      history: [apiHistoryEntry("2026-08-28", "at_risk", 40), apiHistoryEntry("2026-09-09", "isolated")],
    },
  });

  const history = await loadHistory("a b", true, "tefe");

  assert.equal(history.lat, -3.3);
  assert.deepEqual(
    history.assessments.map(({ acquiredOn, status, routeWidthM }) => [acquiredOn, status, routeWidthM]),
    [
      ["2026-08-28", "at_risk", 40],
      ["2026-09-09", "isolated", null],
    ],
  );
  assert.deepEqual(requests, ["./api/v1/aois/tefe/communities/a%20b"]);
});

test("a live history that fails to load is null, never demo data", async (t) => {
  t.mock.method(console, "info", () => {});
  respond({});
  assert.equal(await loadHistory("uarini", true, "tefe"), null);

  globalThis.fetch = async () => {
    throw new TypeError("Failed to fetch");
  };
  assert.equal(await loadHistory("uarini", true, "tefe"), null);
});

test("in demo mode the history comes from the demo data without calling the API", async () => {
  const requests = respond({});

  const history = await loadHistory("uarini", false);

  assert.equal(history.assessments.length, 4);
  assert.deepEqual(requests, []);
});

test("the area comes from the page URL, Tefé by default", () => {
  assert.equal(aoiFromUrl("?aoi=coari&pass=latest"), "coari");
  assert.equal(aoiFromUrl(""), "tefe");
  assert.equal(aoiFromUrl("?aoi=../etc"), "tefe");
});
