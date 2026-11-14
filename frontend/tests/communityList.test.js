import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { community, installDom } from "./helpers/browser.js";
import { renderCommunityList, renderSelectedSummary } from "../src/components/communityList.js";

const communities = [
  community({ id: "nogueira", name: "Nogueira", kind: "village", latest: { status: "isolated", routeWidthM: null } }),
  community({ id: "uarini", name: "Uarini", kind: "town", latest: { status: "at_risk", routeWidthM: 40 } }),
];
let container;

beforeEach(() => {
  container = installDom('<div id="list"></div>').querySelector("#list");
});

test("renders one numbered button per community with its kind, status and route width", () => {
  renderCommunityList(container, communities, "nogueira", () => {});

  const [first, second] = container.querySelectorAll("button");
  assert.equal(first.querySelector(".site-index").textContent, "01");
  assert.equal(first.querySelector(".site-main strong").textContent, "Nogueira");
  assert.equal(first.querySelector(".site-main small").textContent, "Village");
  assert.equal(first.querySelector(".site-change b").textContent, "Isolated");
  assert.ok(first.querySelector(".site-change b").classList.contains("status-isolated"));
  assert.equal(first.querySelector(".site-change small").textContent, "—");
  assert.equal(second.querySelector(".site-change b").textContent, "At risk");
  assert.equal(second.querySelector(".site-change small").textContent, "40 m");
});

test("marks only the selected community as pressed", () => {
  renderCommunityList(container, communities, "uarini", () => {});

  const [first, second] = container.querySelectorAll("button");
  assert.equal(first.getAttribute("aria-pressed"), "false");
  assert.equal(second.getAttribute("aria-pressed"), "true");
  assert.ok(second.classList.contains("is-selected"));
});

test("clicking a community reports its id", () => {
  const selected = [];
  renderCommunityList(container, communities, "nogueira", (id) => selected.push(id));

  container.querySelectorAll("button")[1].click();

  assert.deepEqual(selected, ["uarini"]);
});

test("rendering again replaces the previous list", () => {
  renderCommunityList(container, communities, "nogueira", () => {});
  renderCommunityList(container, communities.slice(0, 1), "nogueira", () => {});

  assert.equal(container.querySelectorAll("button").length, 1);
});

test("the summary tells when NISAR last saw the community", () => {
  renderSelectedSummary(container, community({ name: "Uarini", latest: { acquiredOn: "2026-08-28" } }));

  assert.equal(container.querySelector("h3").textContent, "Uarini");
  assert.equal(container.querySelector("p").textContent, "Last seen by NISAR on 28 Aug 2026.");
  assert.equal(container.querySelector(".confidence").textContent, "Town");
});

test("the summary of a community never assessed says so", () => {
  renderSelectedSummary(container, community({ latest: null }));

  assert.equal(container.querySelector("p").textContent, "Not assessed by any pass yet.");
});
