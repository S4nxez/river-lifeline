// "On this pass": the pass on screen in figures, each with how it moved since the pass before.
import { escapeHtml, formatChange, formatLevel, formatShortDay } from "../format.js";
import { STATUSES, countByStatus } from "../status.js";
import { glyph } from "./glyph.js";

const ROWS = [
  ["isolated", "isolated"],
  ["at_risk", "atRisk"],
  ["connected", "connected"],
];

/** The note under a figure: its change since the previous pass, or that there is none to compare with. */
function changeNote(now, before, previous, unit = "") {
  if (!previous || before == null || now == null) return "first pass on record";
  const change = formatChange(now - before, unit);
  return change === "no change" ? change : `<b>${change}</b> since ${formatShortDay(previous.acquiredOn)}`;
}

/**
 * Renders the ledger of `pass` (its `communities` as loaded) against `previous`, the pass before it (or null).
 * `gaugeName` names the river gauge; without a river level the row is left out.
 */
export function renderLedger(container, { pass, previous, communities, gaugeName }) {
  if (!pass) {
    container.innerHTML = "";
    return;
  }
  const counts = countByStatus(communities);
  const watched = communities.length;
  const rows = ROWS.map(([status, key]) => {
    const now = counts[status];
    return `<div class="ledger-row">
      <dt>${glyph(status, 11)}<span>${STATUSES[status].label}</span></dt>
      <dd class="ledger-value">${now} <small>/ ${watched}</small></dd>
      <dd class="ledger-note">${changeNote(now, previous?.counts?.[key], previous)}</dd>
    </div>`;
  });
  if (pass.counts?.changed != null) {
    rows.push(`<div class="ledger-row">
      <dt><span>Changed status</span></dt>
      <dd class="ledger-value">${pass.counts.changed}</dd>
      <dd class="ledger-note">communities whose status moved since the last pass</dd>
    </div>`);
  }
  if (pass.riverLevelCm != null) {
    rows.push(`<div class="ledger-row">
      <dt><span>River at ${escapeHtml(gaugeName ?? "the gauge")}</span></dt>
      <dd class="ledger-value">${formatLevel(pass.riverLevelCm)}</dd>
      <dd class="ledger-note">${changeNote(pass.riverLevelCm, previous?.riverLevelCm, previous, "cm")}</dd>
    </div>`);
  }
  container.innerHTML = `<dl class="ledger">${rows.join("")}</dl>`;
}
