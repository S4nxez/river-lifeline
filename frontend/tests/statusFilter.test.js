import { test } from "node:test";
import assert from "node:assert/strict";
import { community, installDom } from "./helpers/browser.js";
import { renderStatusFilter, toggleStatus } from "../src/components/statusFilter.js";

const ALL = ["isolated", "at_risk", "no_data", "connected"];

test("one toggle per status, most in need first, with how many communities are in it", () => {
  const container = installDom('<div id="filter"></div>').querySelector("#filter");
  const toggled = [];
  const communities = [
    community({ id: "a", latest: { status: "isolated" } }),
    community({ id: "b", latest: { status: "isolated" } }),
    community({ id: "c", latest: { status: "connected" } }),
  ];

  renderStatusFilter(container, communities, new Set(["isolated"]), (status) => toggled.push(status));

  const chips = [...container.querySelectorAll(".filter-chip")];
  assert.deepEqual(
    chips.map((chip) => [chip.dataset.status, chip.querySelector("b").textContent, chip.getAttribute("aria-pressed")]),
    [
      ["isolated", "2", "true"],
      ["at_risk", "0", "false"],
      ["no_data", "0", "false"],
      ["connected", "1", "false"],
    ],
  );
  chips[3].click();
  assert.deepEqual(toggled, ["connected"]);
});

test("toggling hides a shown status and shows a hidden one", () => {
  assert.deepEqual([...toggleStatus(new Set(ALL), "connected")], ["isolated", "at_risk", "no_data"]);
  assert.deepEqual([...toggleStatus(new Set(["isolated"]), "at_risk")], ["isolated", "at_risk"]);
});

test("turning the last status off shows them all again, so the map is never empty by mistake", () => {
  assert.deepEqual([...toggleStatus(new Set(["isolated"]), "isolated")], ALL);
});
