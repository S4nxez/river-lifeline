import { test } from "node:test";
import assert from "node:assert/strict";
import { community } from "./helpers/browser.js";
import { loadHistory, loadOverview } from "../src/services/api.js";

function respond(body, ok = true) {
  const requests = [];
  globalThis.fetch = async (url) => {
    requests.push(url);
    return { ok, json: async () => body };
  };
  return requests;
}

test("the overview comes from the API when it has communities", async () => {
  const overview = { hub: null, latestPass: null, communities: [community()] };
  const requests = respond(overview);

  assert.deepEqual(await loadOverview(), { overview, live: true });
  assert.deepEqual(requests, ["./api/communities"]);
});

test("the overview falls back to the demo data when the API is down or empty", async (t) => {
  t.mock.method(console, "info", () => {});
  globalThis.fetch = async () => {
    throw new TypeError("Failed to fetch");
  };
  const down = await loadOverview();
  respond({ communities: [] });
  const empty = await loadOverview();
  respond({}, false);
  const failing = await loadOverview();

  for (const result of [down, empty, failing]) {
    assert.equal(result.live, false);
    assert.equal(result.overview.hub.name, "Tefé");
  }
});

test("a live history comes from the API, with the id escaped", async () => {
  const requests = respond({ id: "a b", assessments: [] });

  assert.deepEqual(await loadHistory("a b", true), { id: "a b", assessments: [] });
  assert.deepEqual(requests, ["./api/communities/a%20b"]);
});

test("a live history that fails to load is null, never demo data", async (t) => {
  t.mock.method(console, "info", () => {});
  respond({}, false);
  assert.equal(await loadHistory("uarini", true), null);

  globalThis.fetch = async () => {
    throw new TypeError("Failed to fetch");
  };
  assert.equal(await loadHistory("uarini", true), null);
});

test("in demo mode the history comes from the demo data without calling the API", async () => {
  const requests = respond({});

  const history = await loadHistory("uarini", false);

  assert.equal(history.assessments.length, 4);
  assert.deepEqual(requests, []);
});
