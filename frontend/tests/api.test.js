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
} from "./helpers/browser.js";
import {
  aoiFromUrl,
  boundsOf,
  exportUrl,
  imageryOf,
  layerUrl,
  loadHistory,
  loadOverview,
  loadPass,
  loadRiverLevel,
  plateOf,
} from "../src/services/api.js";

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
  return {
    [BASE]: aoi,
    [`${BASE}/summary`]: summary,
    [`${BASE}/passes/latest/communities`]: collection,
    "./api/v1/meta": apiMeta(),
    [`${BASE}/passes`]: {
      items: [
        apiPassItem("2026-09-05", { evaluated: false, coverage: 0.55, riverLevelM: 10.1 }),
        apiPassItem("2026-09-09"),
      ],
    },
    [`${BASE}/passes/latest`]: apiPassDetail("2026-09-09"),
  };
}

test("the overview is built from the AOI, its summary and the latest pass's communities", async () => {
  const caiambe = apiFeature({
    id: "caiambe",
    name: "Caiambé",
    status: "isolated",
    width: null,
    reason: "no_route_confirmed",
    previousStatus: "at_risk",
    boardingPoint: { location: [-64.4, -3.5], source: "port" },
  });
  const requests = respond(overviewRoutes([caiambe]));

  const { overview, live } = await loadOverview("tefe");

  assert.equal(live, true);
  assert.deepEqual(overview.hub, { id: "tefe", name: "Tefé", kind: "hub", lat: -3.354, lng: -64.711 });
  assert.equal(overview.passId, "nisar-2026-09-09");
  assert.equal(overview.recordUpdatedAt, "2026-11-15T04:54:27Z");
  assert.deepEqual(overview.communities, [
    {
      id: "caiambe",
      name: "Caiambé",
      kind: "town",
      lat: -2.99,
      lng: -65.113,
      changed: true,
      previousStatus: "at_risk",
      boardingSource: "port",
      latest: {
        passId: "nisar-2026-09-09",
        acquiredOn: "2026-09-09",
        status: "isolated",
        reason: "no_route_confirmed",
        routeWidthM: null,
        sentinel1: null,
      },
    },
  ]);
  assert.deepEqual(requests.sort(), [
    BASE,
    `${BASE}/passes`,
    `${BASE}/passes/latest`,
    `${BASE}/passes/latest/communities`,
    `${BASE}/summary`,
    "./api/v1/meta",
  ]);
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

test("the area comes from the API: its circle, bounds in Leaflet's order, width classes and gauge", async () => {
  respond(overviewRoutes([apiFeature()]));

  const { overview } = await loadOverview("tefe");

  assert.deepEqual(overview.area, {
    id: "tefe",
    name: "Tefé",
    region: "Amazonas",
    center: { lat: -3.4, lng: -64.75 },
    radiusKm: 55,
    bounds: [
      [-3.9, -65.2],
      [-2.9, -64.3],
    ],
    connectedMinWidthM: 60,
    widthClassesM: [40, 60, 100, 200, 400],
    gauge: { name: "Tefé-Missões", provider: "ANA" },
  });
});

test("a boarding point without a source is still named, and none means the place itself", async () => {
  respond(
    overviewRoutes([
      apiFeature({ id: "a", boardingPoint: { location: [-64.4, -3.5], source: null } }),
      apiFeature({ id: "b" }),
    ]),
  );

  const { overview } = await loadOverview("tefe");

  assert.deepEqual(
    overview.communities.map((community) => community.boardingSource),
    ["boarding point", null],
  );
});

test("lists the passes oldest first, with their orbit, counts and whether they could be judged", async () => {
  respond(overviewRoutes([apiFeature()]));

  const { overview } = await loadOverview("tefe");

  assert.equal(overview.passId, "nisar-2026-09-09");
  assert.deepEqual(
    overview.passes.map(({ id, evaluated, coverage, riverLevelCm, orbitDirection }) => [
      id,
      evaluated,
      coverage,
      riverLevelCm,
      orbitDirection,
    ]),
    [
      ["nisar-2026-09-05", false, 0.55, 1010, "descending"],
      ["nisar-2026-09-09", true, 0.97, 980, "descending"],
    ],
  );
  assert.deepEqual(overview.passes[1].counts, {
    monitored: 4,
    connected: 1,
    atRisk: 1,
    isolated: 2,
    noData: 0,
    changed: 1,
  });
  assert.equal(overview.passes[1].acquiredAt, "2026-09-09T09:45:12Z");
});

test("each pass keeps the layers the API lists for it, or null when it lists none", async () => {
  const routes = overviewRoutes([apiFeature()]);
  const [first, second] = routes[`${BASE}/passes`].items;
  respond({
    ...routes,
    [`${BASE}/passes`]: { items: [{ ...first, layer_ids: ["radar_hh", "sentinel1_vv"] }, second] },
  });

  const { overview } = await loadOverview("tefe");

  assert.deepEqual(overview.passes[0].layerIds, ["radar_hh", "sentinel1_vv"]);
  assert.equal(overview.passes[1].layerIds, null);
});

test("the plate of the radar image comes from the pass's image card", async () => {
  respond(overviewRoutes([apiFeature()]));

  const { overview } = await loadOverview("tefe");

  assert.deepEqual(overview.plate, {
    platform: "NISAR",
    band: "L",
    polarizations: ["HH", "HV"],
    track: 97,
    orbitDirection: "descending",
    acquiredAt: "2026-09-09T22:41:00Z",
    pixelSizeM: 20,
    coverage: 0.99,
    product: "GCOV",
  });
  assert.equal(plateOf(null), null);
  assert.equal(plateOf({ platform: "NISAR", track: 5 }).track, 5);
});

test("the river level comes in centimetres, oldest first, without empty readings", async () => {
  respond({
    [`${BASE}/river-level`]: apiRiverLevel([
      ["2026-08-28", 11.8],
      ["2026-08-29", null],
      ["2026-09-09", 9.8],
    ]),
  });

  const river = await loadRiverLevel(true, "tefe");

  assert.equal(river.stationName, "Tefé-Missões");
  assert.deepEqual(river.readings, [
    { date: "2026-08-28", levelCm: 1180 },
    { date: "2026-09-09", levelCm: 980 },
  ]);
});

test("a river level that fails is null; demo mode has its own", async (t) => {
  t.mock.method(console, "info", () => {});
  respond({});
  assert.equal(await loadRiverLevel(true, "tefe"), null);
  assert.ok((await loadRiverLevel(false)).readings.length > 0);
});

test("a day's download is the backend's export of the pass, with the id escaped", () => {
  assert.equal(exportUrl("a b", "csv", "tefe"), "./api/v1/aois/tefe/passes/a%20b/export?format=csv");
});

test("a card that isn't monitored says so, with its reason", async () => {
  respond({
    "./api/v1/aois/tefe/communities/x": {
      id: "x",
      name: "X",
      monitored: false,
      exclusion_reason: "inland",
      history: [],
    },
  });

  const history = await loadHistory("x", true, "tefe");

  assert.equal(history.monitored, false);
  assert.equal(history.exclusionReason, "inland");
  assert.equal(history.lat, undefined);
});

test("the latest pass's imagery is named from the catalog and read relative to the page", async () => {
  respond(overviewRoutes([apiFeature()]));

  const { overview } = await loadOverview("tefe");

  assert.deepEqual(
    overview.imagery.map(({ id, label, url, defaultVisible, defaultOpacity }) => [
      id,
      label,
      url,
      defaultVisible,
      defaultOpacity,
    ]),
    [
      ["radar_hh", "Radar (HH)", "./api/v1/aois/tefe/passes/nisar-2026-09-09/layers/radar_hh.png", true, 1],
      ["water", "Open water", "./api/v1/aois/tefe/passes/nisar-2026-09-09/layers/water.png", true, 0.8],
      ["change", "Change vs previous pass", "./api/v1/aois/tefe/passes/nisar-2026-09-09/layers/change.png", false, 0.9],
    ],
  );
  assert.deepEqual(overview.imagery[0].bounds, [
    [-3.9, -65.2],
    [-2.9, -64.3],
  ]);
});

test("the monitor still shows live when only the catalog, the pass list or the imagery fail", async (t) => {
  t.mock.method(console, "info", () => {});
  const routes = overviewRoutes([apiFeature()]);
  delete routes["./api/v1/meta"];
  delete routes[`${BASE}/passes`];
  delete routes[`${BASE}/passes/latest`];
  respond(routes);

  const { overview, live } = await loadOverview("tefe");

  assert.equal(live, true);
  assert.deepEqual(overview.passes, []);
  assert.deepEqual(overview.layerCatalog, []);
  assert.deepEqual(overview.imagery, []);
});

test("a layer the catalog doesn't know still shows, by its id and hidden at first", () => {
  const imagery = imageryOf(apiPassDetail("2026-09-09", ["sentinel1_vv"]), []);

  assert.deepEqual(
    imagery.map(({ id, label, defaultVisible }) => [id, label, defaultVisible]),
    [["sentinel1_vv", "sentinel1_vv", false]],
  );
});

test("a layer without a url or valid bounds is left out", () => {
  const detail = {
    layers: [
      { id: "water", url: null, bounds: [1, 2, 3, 4] },
      { id: "change", url: "/x.png", bounds: [1] },
    ],
  };

  assert.deepEqual(imageryOf(detail, []), []);
  assert.deepEqual(imageryOf(null, []), []);
});

test("layer URLs from the server root are read relative to the page; full URLs are kept", () => {
  assert.equal(layerUrl("/api/v1/a/b.png"), "./api/v1/a/b.png");
  assert.equal(layerUrl("https://cdn.example/b.png"), "https://cdn.example/b.png");
  assert.equal(layerUrl("./api/v1/a.png"), "./api/v1/a.png");
});

test("a contract bbox becomes Leaflet bounds; anything else is no bounds", () => {
  assert.deepEqual(boundsOf([-65, -4, -64, -3]), [
    [-4, -65],
    [-3, -64],
  ]);
  assert.equal(boundsOf(null), null);
  assert.equal(boundsOf([1, 2]), null);
});

test("another pass brings its communities and its imagery, with the ids escaped", async () => {
  const requests = respond({
    [`${BASE}/passes/a%20b/communities`]: apiOverview([apiFeature({ status: "connected", width: 100 })], "2026-09-05")
      .collection,
    [`${BASE}/passes/a%20b`]: apiPassDetail("2026-09-05", ["radar_hh"]),
  });
  const catalog = [{ id: "radar_hh", label: "Radar (HH)", defaultVisible: true, defaultOpacity: 1 }];

  const pass = await loadPass("a b", true, catalog, "tefe");

  assert.deepEqual(
    pass.communities.map(({ id, latest }) => [id, latest.status, latest.routeWidthM, latest.acquiredOn]),
    [["uarini", "connected", 100, "2026-09-05"]],
  );
  assert.deepEqual(
    pass.imagery.map(({ id, label }) => [id, label]),
    [["radar_hh", "Radar (HH)"]],
  );
  assert.deepEqual(requests.sort(), [`${BASE}/passes/a%20b`, `${BASE}/passes/a%20b/communities`]);
});

test("a pass whose imagery fails still loads; one whose communities fail is null", async (t) => {
  t.mock.method(console, "info", () => {});
  respond({ [`${BASE}/passes/p/communities`]: apiOverview([apiFeature()]).collection });
  assert.deepEqual((await loadPass("p", true, [], "tefe")).imagery, []);

  respond({ [`${BASE}/passes/p`]: apiPassDetail("2026-09-09") });
  assert.equal(await loadPass("p", true, [], "tefe"), null);
});

test("in demo mode a pass comes from the demo data without calling the API", async () => {
  const requests = respond({});

  const pass = await loadPass("nisar-2026-08-16", false);

  assert.equal(pass.communities.length, 4);
  assert.deepEqual(pass.imagery, []);
  assert.deepEqual(requests, []);
});
