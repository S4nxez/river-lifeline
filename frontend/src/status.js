// What each connectivity status means on screen. Codes are the API's (`status` of an assessment). A status never
// shows by colour alone: each has its own shape (components/glyph.js), as the API's status table also gives them.

export const STATUSES = {
  isolated: { label: "Isolated", shape: "square", color: "#ff6b5b", severity: 0 },
  at_risk: { label: "At risk", shape: "triangle", color: "#f2b544", severity: 1 },
  connected: { label: "Connected", shape: "circle", color: "#7dd3b0", severity: 2 },
  no_data: { label: "No data", shape: "ring", color: "#5d6a86", severity: 3 },
};

/** The statuses, most serious first. */
export const STATUS_ORDER = Object.keys(STATUSES).sort((a, b) => STATUSES[a].severity - STATUSES[b].severity);

export const KIND_LABELS = {
  hub: "Hub",
  town: "Town",
  village: "Village",
  hamlet: "Hamlet",
  indigenous_village: "Indigenous village",
  other: "Community",
};

/** The status code of a community: its latest assessment's, or no_data if it was never assessed. */
export function statusOf(community) {
  return community.latest?.status ?? "no_data";
}

/** A route lost on this pass only: shown and counted as at risk until the next pass confirms it. */
export function isUnconfirmedLoss(community) {
  return community.latest?.reason === "no_route_unconfirmed";
}

/**
 * The most serious first (isolated, at risk, connected, no data), then the narrowest route, then by name. A missing
 * width sorts before every width: within a status it means no route at all.
 */
export function bySeverity(communities) {
  const width = (community) => community.latest?.routeWidthM ?? -1;
  return [...communities].sort(
    (a, b) =>
      STATUSES[statusOf(a)].severity - STATUSES[statusOf(b)].severity ||
      width(a) - width(b) ||
      a.name.localeCompare(b.name),
  );
}

/** How many communities are in each status. */
export function countByStatus(communities) {
  const counts = Object.fromEntries(Object.keys(STATUSES).map((status) => [status, 0]));
  for (const community of communities) counts[statusOf(community)] += 1;
  return counts;
}

/** The latest assessment that saw the community (the backend's rule, for the demo data). */
export function latestKnown(assessments) {
  return [...assessments].reverse().find((item) => item.status !== "no_data") ?? assessments.at(-1) ?? null;
}
