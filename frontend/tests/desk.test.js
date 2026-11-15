// The desk under the pass log: "On this pass" (ledger.js) and the community dossier (dossier.js).
import { test } from "node:test";
import assert from "node:assert/strict";
import { assessment, community, installDom } from "./helpers/browser.js";
import { renderLedger } from "../src/components/ledger.js";
import { barHeight, renderDossier } from "../src/components/dossier.js";

const CLASSES = [40, 60, 100, 200, 400];
const communities = [
  community({ id: "a", latest: { status: "isolated", routeWidthM: null } }),
  community({ id: "b", latest: { status: "at_risk", routeWidthM: 40 } }),
  community({ id: "c", latest: { status: "connected", routeWidthM: 100 } }),
];
const pass = { id: "p2", acquiredOn: "2026-10-23", riverLevelCm: 797, counts: { changed: 2 } };
const previous = {
  id: "p1",
  acquiredOn: "2026-10-11",
  riverLevelCm: 929,
  counts: { isolated: 0, atRisk: 1, connected: 2 },
};

test("the ledger counts each status against the communities watched, with its change since the last pass", () => {
  const container = installDom('<div id="l"></div>').querySelector("#l");

  renderLedger(container, { pass, previous, communities, gaugeName: "Tefé-Missões" });

  const rows = [...container.querySelectorAll(".ledger-row")].map((row) => row.textContent.replace(/\s+/g, " ").trim());
  assert.deepEqual(rows, [
    "Isolated 1 / 3 +1 since 11 Oct",
    "At risk 1 / 3 no change",
    "Connected 1 / 3 −1 since 11 Oct",
    "Changed status 2 communities whose status moved since the last pass",
    "River at Tefé-Missões 797 cm −132 cm since 11 Oct",
  ]);
});

test("the first pass has nothing to compare with, and a pass without a level leaves the river out", () => {
  const container = installDom('<div id="l"></div>').querySelector("#l");

  renderLedger(container, {
    pass: { ...pass, riverLevelCm: null, counts: null },
    previous: null,
    communities,
    gaugeName: null,
  });

  assert.match(container.textContent, /first pass on record/);
  assert.doesNotMatch(container.textContent, /River at/);
});

test("the dossier asks for a pick before one is made", () => {
  const container = installDom('<div id="d"></div>').querySelector("#d");

  renderDossier(container, { community: null });

  assert.match(container.textContent, /Pick a community/);
});

test("the dossier gives the status, the route and when it was last linked to the hub", () => {
  const container = installDom('<div id="d"></div>').querySelector("#d");
  const isolated = community({
    id: "x",
    name: "Canaman",
    kind: "hamlet",
    latest: { acquiredOn: "2026-10-30", passId: "p3", status: "isolated", routeWidthM: null },
  });
  const history = {
    id: "x",
    monitored: true,
    assessments: [
      assessment("2026-10-11", "connected", 100),
      assessment("2026-10-23", "at_risk", 0),
      assessment("2026-10-30", "isolated", null, "no_route_confirmed"),
    ],
  };
  const passes = [
    { id: "nisar-2026-10-11", orbitDirection: "ascending" },
    { id: "nisar-2026-10-30", orbitDirection: "descending" },
  ];

  renderDossier(container, {
    community: isolated,
    history,
    passes,
    passId: "nisar-2026-10-30",
    hubName: "Tefé",
    classesM: CLASSES,
  });

  assert.equal(container.querySelector(".dossier-name").textContent, "Canaman");
  assert.match(container.querySelector(".dossier-status").textContent, /Isolated on 30 Oct 2026/);
  const facts = Object.fromEntries(
    [...container.querySelectorAll(".dossier-facts div")].map((fact) => [
      fact.querySelector("dt").textContent,
      fact.querySelector("dd").textContent,
    ]),
  );
  assert.deepEqual(facts, {
    "Narrowest point of the route": "no route",
    "Last linked to Tefé": "23 Oct 2026",
    "Measured from": "the place itself",
    Type: "Hamlet",
  });
  const bars = [...container.querySelectorAll(".history-bar")];
  assert.equal(bars.length, 3);
  assert.ok(bars[2].classList.contains("is-current"));
  assert.match(bars[0].getAttribute("style"), /--skew:-10deg/);
  assert.match(bars[2].getAttribute("style"), /--skew:10deg/);
  assert.match(container.querySelector(".history-dates").textContent, /11 Oct 2026.*30 Oct 2026/);
});

test("a route lost on one pass only is flagged as waiting for the next", () => {
  const container = installDom('<div id="d"></div>').querySelector("#d");
  const lost = community({ latest: { status: "at_risk", reason: "no_route_unconfirmed", routeWidthM: null } });

  renderDossier(container, { community: lost, history: null, hubName: "Tefé", classesM: CLASSES });

  assert.match(container.textContent, /It counts as isolated if the next pass agrees\./);
  assert.match(container.textContent, /Narrowest point of the route\s*no route/);
});

test("a place left out of the monitor explains why instead", () => {
  const container = installDom('<div id="d"></div>').querySelector("#d");

  renderDossier(container, {
    community: community({ name: "Inland" }),
    history: { monitored: false, exclusionReason: "inland" },
    hubName: "Tefé",
  });
  assert.match(container.textContent, /does not depend on the river/);

  renderDossier(container, {
    community: community(),
    history: { monitored: false, exclusionReason: "unresolvable" },
    hubName: "Tefé",
  });
  assert.match(container.textContent, /too narrow/);
});

test("history bars grow with the width class, and stay low without a route", () => {
  assert.equal(barHeight({ routeWidthM: 0 }, CLASSES), 16);
  assert.equal(barHeight({ routeWidthM: 400 }, CLASSES), 100);
  assert.equal(barHeight({ routeWidthM: null }, CLASSES), 10);
});
