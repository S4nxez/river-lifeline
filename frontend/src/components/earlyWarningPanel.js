// The early warning (CONTRACTS.md, early_warning.json): a hindcast of how many days ahead the river twin would have
// warned which communities lose their route. Its own vocabulary (outlined chips in the gauge's colour), never the
// status colours of the main view: a probability is not an observed status.
import { escapeHtml, formatDay } from "../format.js";

export const HORIZONS = [7, 14, 30];

/** "Median warning: N days before ...; X of Y communities warned; Z false alarms", or what the hindcast lacks. */
export function headline(hindcast) {
  const summary = hindcast?.summary ?? {};
  const observed = summary.observed ?? 0;
  const alarms = summary.false_alarms ?? hindcast?.false_alarms?.length ?? 0;
  const alarmsText = `${alarms} false alarm${alarms === 1 ? "" : "s"}`;
  if (!observed) return `No community was seen cut off by the radar in the hindcast; ${alarmsText}.`;
  const lead = summary.median_lead_days;
  const leadText =
    lead == null
      ? "No community was warned before the radar saw its cut-off"
      : `Median warning: ${Math.round(lead)} days before the radar saw the cut-off`;
  return `${leadText}; ${summary.warned ?? 0} of ${observed} communities warned; ${alarmsText}.`;
}

/** Monitored communities, the soonest expected cut-off first; those the median path never cuts off after them. */
export function sortedCommunities(report) {
  return (report?.communities ?? [])
    .filter((community) => community.class === "monitored")
    .slice()
    .sort((a, b) => {
      const da = a.expected_cutoff_date ?? "9999-12-31";
      const db = b.expected_cutoff_date ?? "9999-12-31";
      return da === db ? a.community_id.localeCompare(b.community_id) : da.localeCompare(db);
    });
}

/** p(isolated) at a horizon as a percentage, or a dash. */
export function isolationChance(community, horizon) {
  const warning = (community.warnings ?? []).find((item) => item.horizon_days === horizon);
  return warning?.p_isolated == null ? "—" : `${Math.round(warning.p_isolated * 100)} %`;
}

/**
 * Fills `container` with the hindcast headline and the communities by expected cut-off. `names` maps community ids to
 * names (from the main view); `onSelect(id)` is called when one is picked. Returns the communities listed.
 */
export function renderEarlyWarningPanel(
  container,
  report,
  { names = {}, selectedId = null, onSelect = () => {} } = {},
) {
  const communities = sortedCommunities(report);
  const rows = communities
    .map((community) => {
      const id = community.community_id;
      const selected = id === selectedId;
      const date = community.expected_cutoff_date
        ? `Cut off ~ ${formatDay(community.expected_cutoff_date)}`
        : "No cut-off expected";
      const chips = HORIZONS.map(
        (h) =>
          `<span class="ew-chip" title="Chance of being cut off within ${h} days">${h} d ${isolationChance(community, h)}</span>`,
      ).join("");
      return `<li><button type="button" class="ew-row${selected ? " is-selected" : ""}" aria-pressed="${selected}"
          data-community="${escapeHtml(id)}"><span class="ew-name">${escapeHtml(names[id] ?? id)}</span>
          <span class="ew-date telemetry">${date}</span><span class="ew-chips">${chips}</span></button></li>`;
    })
    .join("");
  container.innerHTML = `
    <p class="ew-headline">${escapeHtml(headline(report?.hindcast))}</p>
    <p class="ew-issued telemetry">Forecast of ${formatDay(report?.issued_at)} · chance of being cut off within
      ${HORIZONS.join(", ")} days</p>
    <ol class="ew-list">${rows || '<li class="ew-empty">No monitored community.</li>'}</ol>`;
  container
    .querySelectorAll("[data-community]")
    .forEach((button) => button.addEventListener("click", () => onSelect(button.dataset.community)));
  return communities;
}

/** How it works and its limits, as published with it. */
export function renderLimits(container, report) {
  const summary = report?.method?.summary ? `<p>${escapeHtml(report.method.summary)}</p>` : "";
  const limits = report?.method?.limitations ?? [];
  container.innerHTML = `${summary}<ul class="ew-limits-list">${limits.map((limit) => `<li>${escapeHtml(limit)}</li>`).join("")}</ul>`;
}
