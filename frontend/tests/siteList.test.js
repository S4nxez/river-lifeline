import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { installDom, site } from "./helpers/browser.js";
import { renderSiteList, renderSelectedSummary } from "../src/components/siteList.js";

const sites = [
  site({ id: "amazonas", name: "Amazonas basin", level: "high", change: "+18%" }),
  site({ id: "valencia", name: "Valencia coast", level: "low", change: "+3%" }),
];
let container;

beforeEach(() => {
  container = installDom('<div id="list"></div>').querySelector("#list");
});

test("renders one numbered button per site with its level", () => {
  renderSiteList(container, sites, "amazonas", () => {});

  const buttons = container.querySelectorAll("button");
  assert.equal(buttons.length, 2);
  assert.equal(buttons[0].querySelector(".site-index").textContent, "01");
  assert.equal(buttons[1].querySelector(".site-index").textContent, "02");
  assert.match(buttons[0].textContent, /Amazonas basin/);
  assert.equal(buttons[0].querySelector(".site-change small").textContent, "High");
  assert.equal(buttons[1].querySelector(".site-change small").textContent, "Low");
});

test("marks only the selected site as pressed", () => {
  renderSiteList(container, sites, "valencia", () => {});

  const [first, second] = container.querySelectorAll("button");
  assert.equal(first.getAttribute("aria-pressed"), "false");
  assert.equal(second.getAttribute("aria-pressed"), "true");
  assert.ok(second.classList.contains("is-selected"));
  assert.ok(!first.classList.contains("is-selected"));
});

test("clicking a site reports its id", () => {
  const selected = [];
  renderSiteList(container, sites, "amazonas", (id) => selected.push(id));

  container.querySelectorAll("button")[1].click();

  assert.deepEqual(selected, ["valencia"]);
});

test("rendering again replaces the previous list", () => {
  renderSiteList(container, sites, "amazonas", () => {});
  renderSiteList(container, sites.slice(0, 1), "amazonas", () => {});

  assert.equal(container.querySelectorAll("button").length, 1);
});

test("the summary shows the site and its rounded confidence", () => {
  renderSelectedSummary(container, site({ name: "Nile Delta", description: "Seasonal moisture.", confidence: 0.714 }));

  assert.equal(container.querySelector("h3").textContent, "Nile Delta");
  assert.equal(container.querySelector("p").textContent, "Seasonal moisture.");
  assert.equal(container.querySelector(".confidence").textContent, "71% confidence*");
});
