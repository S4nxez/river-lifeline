import { test } from "node:test";
import assert from "node:assert/strict";
import { community } from "./helpers/browser.js";
import { bySeverity, countByStatus, latestKnown, statusOf } from "../src/status.js";

test("a community never assessed has no data", () => {
  assert.equal(statusOf(community({ latest: null })), "no_data");
  assert.equal(statusOf(community({ latest: { status: "isolated" } })), "isolated");
});

test("communities in need come first, then by name", () => {
  const sorted = bySeverity([
    community({ name: "Alvarães", latest: { status: "connected" } }),
    community({ name: "Caiambé", latest: { status: "no_data" } }),
    community({ name: "Uarini", latest: { status: "isolated" } }),
    community({ name: "Nogueira", latest: { status: "at_risk" } }),
    community({ name: "Boca", latest: { status: "isolated" } }),
  ]);

  assert.deepEqual(
    sorted.map((item) => item.name),
    ["Boca", "Uarini", "Nogueira", "Caiambé", "Alvarães"],
  );
});

test("counts the communities in each status", () => {
  const counts = countByStatus([
    community({ latest: { status: "isolated" } }),
    community({ latest: { status: "isolated" } }),
    community({ latest: null }),
  ]);

  assert.deepEqual(counts, { isolated: 2, at_risk: 0, no_data: 1, connected: 0 });
});

test("the latest known assessment skips passes without data", () => {
  const seen = { acquiredOn: "2026-08-28", status: "at_risk" };
  const missed = { acquiredOn: "2026-09-09", status: "no_data" };

  assert.equal(latestKnown([{ acquiredOn: "2026-08-16", status: "connected" }, seen, missed]), seen);
  assert.equal(latestKnown([missed]), missed);
  assert.equal(latestKnown([]), null);
});
