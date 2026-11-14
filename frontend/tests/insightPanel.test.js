import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { installDom, site } from "./helpers/browser.js";
import { renderInsight } from "../src/components/insightPanel.js";

let container;

beforeEach(() => {
  container = installDom('<div id="insight"></div>').querySelector("#insight");
});

const expected = {
  high: { tag: "PRIORITY REVIEW", title: "Significant signal variation" },
  medium: { tag: "COMPARE BASELINE", title: "Moderate signal variation" },
  low: { tag: "ROUTINE MONITORING", title: "Small signal variation" },
};

for (const [level, { tag, title }] of Object.entries(expected)) {
  test(`a ${level} change gets the ${tag} interpretation`, () => {
    renderInsight(container, site({ level }));

    assert.equal(container.querySelector(".insight-status span:last-child").textContent, tag);
    assert.equal(container.querySelector("h3").textContent, title);
    assert.ok(container.querySelector(`.level-bg-${level}`));
  });
}

test("shows the change signal and the rounded confidence", () => {
  renderInsight(container, site({ level: "high", change: "+18%", confidence: 0.816 }));

  const [signal, confidence] = container.querySelectorAll(".insight-metrics strong");
  assert.equal(signal.textContent, "+18%");
  assert.ok(signal.classList.contains("level-high"));
  assert.equal(confidence.textContent, "82%");
});
