import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { community, installDom } from "./helpers/browser.js";
import { renderStatusPanel } from "../src/components/statusPanel.js";

let container;

beforeEach(() => {
  container = installDom('<div id="status"></div>').querySelector("#status");
});

const expected = {
  connected: { tag: "CONNECTED", title: "Water route open" },
  at_risk: { tag: "AT RISK", title: "Route narrowing" },
  isolated: { tag: "ISOLATED", title: "No water route to Tefé" },
  no_data: { tag: "NO DATA", title: "Not observed" },
};

for (const [status, { tag, title }] of Object.entries(expected)) {
  test(`a ${status} community gets the ${tag} interpretation`, () => {
    renderStatusPanel(container, community({ latest: { status } }), { thresholdM: 60, hubName: "Tefé" });

    assert.equal(container.querySelector(".insight-status span:last-child").textContent, tag);
    assert.equal(container.querySelector("h3").textContent, title);
    assert.ok(container.querySelector(`.status-bg-${status}`));
  });
}

test("shows the narrowest point of the route and the pass date", () => {
  renderStatusPanel(container, community({ latest: { status: "at_risk", routeWidthM: 40, acquiredOn: "2026-08-28" } }));

  const [width, pass] = container.querySelectorAll(".insight-metrics strong");
  assert.equal(width.textContent, "40 m");
  assert.ok(width.classList.contains("status-at_risk"));
  assert.equal(pass.textContent, "28 Aug 2026");
});

test("Sentinel-1 confirming the result is reported", () => {
  renderStatusPanel(container, community({ latest: { sentinel1: { sceneOn: "2026-08-27", agrees: true } } }));

  assert.equal(container.querySelector(".crosscheck").textContent, "Sentinel-1 (27 Aug 2026) confirms this result.");
});

test("Sentinel-1 disagreeing asks to verify before acting", () => {
  renderStatusPanel(container, community({ latest: { sentinel1: { sceneOn: "2026-08-27", agrees: false } } }));

  assert.match(container.querySelector(".crosscheck").textContent, /disagrees with NISAR: verify on the ground/);
});

test("without a Sentinel-1 scene there is no cross-check", () => {
  renderStatusPanel(container, community({ latest: { sentinel1: null } }));

  assert.match(container.querySelector(".crosscheck").textContent, /No Sentinel-1 scene/);
});

test("a community never assessed shows no data", () => {
  renderStatusPanel(container, community({ latest: null }));

  assert.equal(container.querySelector("h3").textContent, "Not observed");
  const [width, pass] = container.querySelectorAll(".insight-metrics strong");
  assert.equal(width.textContent, "—");
  assert.equal(pass.textContent, "—");
});

test("the explanation uses the area's connected width and the name of the town the route leads to", () => {
  renderStatusPanel(container, community({ latest: { status: "connected" } }), { thresholdM: 100, hubName: "Coari" });
  assert.equal(
    container.querySelector(".insight-copy").textContent,
    "A channel at least 100 m wide still links the community to Coari.",
  );

  renderStatusPanel(container, community({ latest: { status: "at_risk" } }), { thresholdM: 100, hubName: "Coari" });
  assert.match(container.querySelector(".insight-copy").textContent, /narrower than 100 m/);
});

test("without the area's figures the explanation still reads", () => {
  renderStatusPanel(container, community({ latest: { status: "connected" } }));

  assert.equal(
    container.querySelector(".insight-copy").textContent,
    "A channel wide enough for boats still links the community to the hub.",
  );
});
