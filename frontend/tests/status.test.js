import { test } from "node:test";
import assert from "node:assert/strict";
import { community } from "./helpers/browser.js";
import {
  STATUSES,
  STATUS_ORDER,
  bySeverity,
  countByStatus,
  isUnconfirmedLoss,
  latestKnown,
  statusOf,
} from "../src/status.js";

test("a community never assessed has no data", () => {
  assert.equal(statusOf(community({ latest: null })), "no_data");
  assert.equal(statusOf(community({ latest: { status: "isolated" } })), "isolated");
});

test("every status has its own shape, so colour is never the only cue", () => {
  const shapes = Object.values(STATUSES).map((status) => status.shape);
  assert.equal(new Set(shapes).size, shapes.length);
  assert.deepEqual(STATUS_ORDER, ["isolated", "at_risk", "connected", "no_data"]);
});

test("the most serious first, then the narrowest route, then by name", () => {
  const sorted = bySeverity([
    community({ name: "Alvarães", latest: { status: "connected", routeWidthM: 400 } }),
    community({ name: "Bom Jesus", latest: { status: "connected", routeWidthM: 60 } }),
    community({ name: "Caiambé", latest: { status: "no_data", routeWidthM: null } }),
    community({ name: "Uarini", latest: { status: "isolated", routeWidthM: null } }),
    community({ name: "Nogueira", latest: { status: "at_risk", routeWidthM: 40 } }),
    community({ name: "Jutica", latest: { status: "at_risk", routeWidthM: 0 } }),
    community({ name: "Boca", latest: { status: "isolated", routeWidthM: null } }),
  ]);

  assert.deepEqual(
    sorted.map((item) => item.name),
    ["Boca", "Uarini", "Jutica", "Nogueira", "Bom Jesus", "Alvarães", "Caiambé"],
  );
});

test("a route lost on this pass only is an unconfirmed loss", () => {
  assert.equal(isUnconfirmedLoss(community({ latest: { status: "at_risk", reason: "no_route_unconfirmed" } })), true);
  assert.equal(isUnconfirmedLoss(community({ latest: { status: "at_risk", reason: "narrow_route" } })), false);
  assert.equal(isUnconfirmedLoss(community({ latest: null })), false);
});

test("counts the communities in each status", () => {
  const counts = countByStatus([
    community({ latest: { status: "isolated" } }),
    community({ latest: { status: "isolated" } }),
    community({ latest: null }),
  ]);

  assert.deepEqual(counts, { isolated: 2, at_risk: 0, connected: 0, no_data: 1 });
});

test("the latest known assessment skips passes without data", () => {
  const seen = { acquiredOn: "2026-08-28", status: "at_risk" };
  const missed = { acquiredOn: "2026-09-09", status: "no_data" };

  assert.equal(latestKnown([{ acquiredOn: "2026-08-16", status: "connected" }, seen, missed]), seen);
  assert.equal(latestKnown([missed]), missed);
  assert.equal(latestKnown([]), null);
});
