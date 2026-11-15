import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { community, installDom } from "./helpers/browser.js";
import {
  FILTERS,
  changeNote,
  filterCommunities,
  renderCommunityList,
  renderListFilters,
} from "../src/components/communityList.js";

const CLASSES = [40, 60, 100, 200, 400];
const communities = [
  community({ id: "nogueira", name: "Nogueira", kind: "village", latest: { status: "isolated", routeWidthM: null } }),
  community({
    id: "uarini",
    name: "Uarini",
    kind: "town",
    changed: true,
    previousStatus: "connected",
    latest: { status: "at_risk", routeWidthM: 40 },
  }),
  community({ id: "alvaraes", name: "Alvarães", kind: "hamlet", latest: { status: "connected", routeWidthM: 400 } }),
];
let container;

beforeEach(() => {
  container = installDom('<div id="list"></div><div id="filters"></div>').querySelector("#list");
});

test("a row per community: its glyph, name, kind and route width class", () => {
  renderCommunityList(container, { communities, selectedId: null, classesM: CLASSES }, () => {});

  const [first, second, third] = container.querySelectorAll("button");
  assert.ok(first.querySelector("svg.glyph-isolated.glyph-square"));
  assert.equal(first.querySelector(".site-main strong").textContent, "Nogueira");
  assert.equal(first.querySelector(".site-main small").textContent, "Village");
  assert.equal(first.querySelector(".site-route b").textContent, "no route");
  assert.equal(second.querySelector(".site-route b").textContent, "40–60 m");
  assert.equal(third.querySelector(".site-route b").textContent, "≥ 400 m");
  assert.equal(first.querySelector(".visually-hidden").textContent, "Isolated");
});

test("a change since the last pass shows under the route, in the row", () => {
  renderCommunityList(container, { communities, selectedId: null, classesM: CLASSES }, () => {});

  const notes = [...container.querySelectorAll("button")].map(
    (row) => row.querySelector(".site-route small")?.textContent,
  );
  assert.deepEqual(notes, [undefined, "was connected", undefined]);
});

test("a route lost on this pass only is flagged before any other change", () => {
  const lost = community({ changed: true, previousStatus: "connected", latest: { reason: "no_route_unconfirmed" } });

  assert.equal(changeNote(lost), "route lost, to confirm");
  assert.equal(changeNote(community({ changed: false })), "");
});

test("marks only the selected community as pressed, and reports clicks", () => {
  const picked = [];
  renderCommunityList(container, { communities, selectedId: "uarini", classesM: CLASSES }, (id) => picked.push(id));

  const rows = [...container.querySelectorAll("button")];
  assert.deepEqual(
    rows.map((row) => row.getAttribute("aria-pressed")),
    ["false", "true", "false"],
  );
  assert.ok(rows[1].classList.contains("is-selected"));
  rows[2].click();
  assert.deepEqual(picked, ["alvaraes"]);
});

test("names from the data are escaped", () => {
  renderCommunityList(container, { communities: [community({ name: "<img src=x>" })], classesM: CLASSES }, () => {});

  assert.equal(container.querySelector("img"), null);
  assert.equal(container.querySelector(".site-main strong").textContent, "<img src=x>");
});

test("says so when nothing matches, naming the day", () => {
  renderCommunityList(container, { communities: [], dayLabel: "23 Oct" }, () => {});

  assert.equal(container.textContent, "No community matches this filter on 23 Oct.");
});

test("filters by status or change, and searches names without caring for accents or case", () => {
  const ids = (options) => filterCommunities(communities, options).map((item) => item.id);

  assert.deepEqual(ids({ filter: "all" }), ["nogueira", "uarini", "alvaraes"]);
  assert.deepEqual(ids({ filter: "isolated" }), ["nogueira"]);
  assert.deepEqual(ids({ filter: "changed" }), ["uarini"]);
  assert.deepEqual(ids({ query: "ALVARAES" }), ["alvaraes"]);
  assert.deepEqual(ids({ filter: "at_risk", query: "nog" }), []);
});

test("one filter pill at a time, and every click reported", () => {
  const filters = document.querySelector("#filters");
  const chosen = [];
  renderListFilters(filters, "isolated", (id) => chosen.push(id));

  const pills = [...filters.querySelectorAll("button")];
  assert.deepEqual(
    pills.map((pill) => pill.textContent),
    FILTERS.map((filter) => filter.label),
  );
  assert.deepEqual(
    pills.map((pill) => pill.getAttribute("aria-pressed")),
    ["false", "true", "false", "false"],
  );
  pills[3].click();
  assert.deepEqual(chosen, ["changed"]);
});
