// "Community": the dossier of the selected community on the pass on screen, with its route on every evaluated pass.
import { escapeHtml, formatDay, widthClassLabel } from "../format.js";
import { KIND_LABELS, STATUSES, isUnconfirmedLoss, statusOf } from "../status.js";
import { glyph } from "./glyph.js";

const SKEW = { ascending: -10, descending: 10 };

/**
 * A bar's height (%) for an assessment: from 16 % for the narrowest width class to 100 % for the widest, and 10 % when
 * the pass found no route or didn't see the community.
 */
export function barHeight(assessment, classesM) {
  if (assessment.routeWidthM == null) return 10;
  const levels = [0, ...[...classesM].sort((a, b) => a - b)];
  const rank = Math.max(0, levels.indexOf(assessment.routeWidthM));
  return levels.length < 2 ? 100 : Math.round(16 + (rank / (levels.length - 1)) * 84);
}

/** Why a place isn't monitored, in the page's words when the reason is one it knows. */
function exclusionText(reason) {
  const code = String(reason ?? "").toLowerCase();
  if (code.includes("inland") || code.includes("no_water") || code.includes("not_river")) {
    return "No water nearby on any pass: this place does not depend on the river, so it is not monitored.";
  }
  if (code.includes("unresolv") || code.includes("narrow") || code.includes("resolution")) {
    return "No pass shows a water route to the hub that the radar can resolve at 20 m: the streams here are too narrow. Its status would be a guess, so it is left out of the counts.";
  }
  return "This place is not monitored, so it is left out of the counts.";
}

/** The latest pass up to `day` on which the community still had a route, or null. */
function lastLinked(assessments, day) {
  return (
    [...assessments]
      .reverse()
      .find((item) => item.acquiredOn <= day && (item.status === "connected" || item.status === "at_risk")) ?? null
  );
}

function historyBars(history, passes, passId, classesM) {
  const assessments = history?.assessments ?? [];
  if (assessments.length === 0) return "";
  const direction = new Map(passes.map((pass) => [pass.id, pass.orbitDirection]));
  const bars = assessments
    .map((item) => {
      const skew = SKEW[direction.get(item.passId)] ?? 0;
      const label = `${formatDay(item.acquiredOn)}: ${STATUSES[item.status]?.label ?? item.status}, ${widthClassLabel(item.routeWidthM, item.status, classesM, item.reason)}`;
      return `<li class="history-bar history-${item.status}${item.passId === passId ? " is-current" : ""}" style="--h:${barHeight(item, classesM)}%;--skew:${skew}deg" title="${escapeHtml(label)}"><span class="visually-hidden">${escapeHtml(label)}</span></li>`;
    })
    .join("");
  return `<div class="history">
    <p class="telemetry">Route width on every evaluated pass</p>
    <ol class="history-bars">${bars}</ol>
    <p class="history-dates telemetry"><span>${formatDay(assessments[0].acquiredOn)}</span><span>${formatDay(assessments.at(-1).acquiredOn)}</span></p>
  </div>`;
}

/**
 * Renders the dossier. `community` is the selected one as the pass on screen saw it (null: nothing picked yet) and
 * `history` its card (null while loading or when it failed).
 */
export function renderDossier(container, { community, history, passes = [], passId, hubName, classesM = [] }) {
  if (!community) {
    container.innerHTML = `<h3 class="dossier-name">Pick a community</h3>
      <p class="dossier-copy">Select a community on the map or in the list to see its water route on every pass.</p>`;
    return;
  }
  const status = statusOf(community);
  const latest = community.latest;
  const name = `<h3 class="dossier-name">${escapeHtml(community.name)}</h3>`;
  if (history && history.monitored === false) {
    container.innerHTML = `${name}<p class="dossier-copy">${escapeHtml(exclusionText(history.exclusionReason))}</p>`;
    return;
  }
  const day = latest?.acquiredOn;
  const statusLine = day ? `${STATUSES[status].label} on ${formatDay(day)}` : "Not assessed by any pass yet";
  const unconfirmed = isUnconfirmedLoss(community)
    ? `<p class="dossier-warning">No water route on this pass. It counts as isolated if the next pass agrees.</p>`
    : "";
  const linked = day && history ? lastLinked(history.assessments ?? [], day) : null;
  const linkedText = !history
    ? null
    : status === "connected" || status === "at_risk"
      ? "on this pass"
      : linked
        ? formatDay(linked.acquiredOn)
        : "not on record";
  const facts = [
    ["Narrowest point of the route", widthClassLabel(latest?.routeWidthM ?? null, status, classesM, latest?.reason)],
    linkedText ? [`Last linked to ${hubName}`, linkedText] : null,
    ["Measured from", community.boardingSource ?? "the place itself"],
    ["Type", KIND_LABELS[community.kind] ?? "Community"],
  ]
    .filter(Boolean)
    .map(([label, value]) => `<div><dt class="telemetry">${escapeHtml(label)}</dt><dd>${escapeHtml(value)}</dd></div>`)
    .join("");

  container.innerHTML = `${name}
    <p class="dossier-status">${glyph(status, 11)}<span>${escapeHtml(statusLine)}</span></p>
    ${unconfirmed}
    <dl class="dossier-facts">${facts}</dl>
    ${historyBars(history, passes, passId, classesM)}`;
}
