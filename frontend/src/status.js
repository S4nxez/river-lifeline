// What each connectivity status means on screen. Codes are the API's (`status` of an assessment).

export const STATUSES = {
  isolated: { label: "Isolated", color: "#b42318", severity: 0 },
  at_risk: { label: "At risk", color: "#a15c07", severity: 1 },
  no_data: { label: "No data", color: "#8a8f96", severity: 2 },
  connected: { label: "Connected", color: "#0b7a52", severity: 3 },
};

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

/** Communities in need first (isolated, at risk, no data, connected), then by name. */
export function bySeverity(communities) {
  return [...communities].sort(
    (a, b) => STATUSES[statusOf(a)].severity - STATUSES[statusOf(b)].severity || a.name.localeCompare(b.name),
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
