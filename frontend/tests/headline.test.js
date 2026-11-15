import { test } from "node:test";
import assert from "node:assert/strict";
import { community, installDom } from "./helpers/browser.js";
import { headlineMeta, headlineSentence, headlineSub, renderHeadline } from "../src/components/headline.js";

const pass = { id: "p", acquiredOn: "2026-10-23", evaluated: true, coverage: 1 };
const isolated = (id) => community({ id, latest: { status: "isolated", routeWidthM: null } });
const atRisk = (id, reason = "narrow_route") => community({ id, latest: { status: "at_risk", reason } });
const connected = (id) => community({ id, latest: { status: "connected", routeWidthM: 100 } });

test("the meta line names the pass and how many it watched", () => {
  assert.equal(headlineMeta(pass, 132), "23 Oct 2026 · 132 communities watched");
  assert.equal(headlineMeta(pass, 1), "23 Oct 2026 · 1 community watched");
});

test("a day with no pass of its own says which pass it shows, and how far it is", () => {
  assert.equal(
    headlineMeta(pass, 132, "2026-10-30"),
    "23 Oct 2026 · 132 communities watched · latest pass before 30 Oct, 7 days earlier",
  );
  assert.equal(
    headlineMeta(pass, 132, "2026-10-22"),
    "23 Oct 2026 · 132 communities watched · no full pass before 22 Oct: first one 1 day later",
  );
});

test("the sentence agrees with its count, and none is said as a good thing", () => {
  assert.deepEqual(headlineSentence(9, "Tefé"), { count: 9, text: "communities have lost the water route to Tefé." });
  assert.deepEqual(headlineSentence(1, "Tefé"), { count: 1, text: "community has lost the water route to Tefé." });
  assert.equal(headlineSentence(0, "Tefé").text, "communities have lost their water route to Tefé.");
});

test("the line under it counts the narrow routes and the routes lost for the first time", () => {
  assert.equal(
    headlineSub([atRisk("a"), atRisk("b", "no_route_unconfirmed"), connected("c")], 60),
    "2 more can only reach it through channels narrower than 60 m, 1 of them with no route on this pass for the first time.",
  );
  assert.equal(headlineSub([atRisk("a")], 60), "1 more can only reach it through channels narrower than 60 m.");
  assert.equal(headlineSub([connected("c")], 60), "Every other watched community has a route at least 60 m wide.");
  assert.equal(headlineSub([connected("c")], null), "Every other watched community still has a wide water route.");
});

test("renders the count apart, in the alert colour when communities are cut off", () => {
  const container = installDom('<div id="h"></div>').querySelector("#h");

  renderHeadline(container, {
    pass,
    communities: [isolated("a"), isolated("b"), atRisk("c"), connected("d")],
    hubName: "Tefé",
    thresholdM: 60,
  });

  const count = container.querySelector(".headline-count");
  assert.equal(count.textContent, "2");
  assert.ok(count.classList.contains("headline-count-alert"));
  assert.match(
    container.querySelector(".headline-title").textContent,
    /2 communities have lost the water route to Tefé\./,
  );
});

test("a partial pass and no pass at all are said plainly", () => {
  const container = installDom('<div id="h"></div>').querySelector("#h");

  renderHeadline(container, { pass: { ...pass, evaluated: false, coverage: 0.64 }, communities: [], hubName: "Tefé" });
  assert.match(container.textContent, /This pass covers only part of the area\..*\(64 % covered\)/s);

  renderHeadline(container, { pass: null, communities: [], hubName: "Tefé" });
  assert.match(container.textContent, /No NISAR pass over the area yet\./);
});

test("names from the data are escaped", () => {
  const container = installDom('<div id="h"></div>').querySelector("#h");

  renderHeadline(container, { pass, communities: [connected("a")], hubName: "<i>x</i>", thresholdM: 60 });

  assert.equal(container.querySelector("i"), null);
});
