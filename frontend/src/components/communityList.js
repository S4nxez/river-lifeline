// The community list beside the map: a search, one filter at a time, and a row per community, most in need first.
import { escapeHtml, widthClassLabel } from "../format.js";
import { KIND_LABELS, STATUSES, isUnconfirmedLoss, statusOf } from "../status.js";
import { glyph } from "./glyph.js";

export const FILTERS = [
  { id: "all", label: "All" },
  { id: "isolated", label: "Isolated" },
  { id: "at_risk", label: "At risk" },
  { id: "changed", label: "Changed" },
];

/** Lower case, without accents: "Alvarães" and "alvaraes" match. */
const plain = (text) => String(text).normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();

/** The communities a filter (`all`, a status, or `changed`) and a search keep, in their order. */
export function filterCommunities(communities, { filter = "all", query = "" } = {}) {
  const wanted = plain(query.trim());
  return communities.filter((community) => {
    if (filter === "changed" && !community.changed) return false;
    if (filter !== "all" && filter !== "changed" && statusOf(community) !== filter) return false;
    return wanted === "" || plain(community.name).includes(wanted);
  });
}

/** The amber note under a route: a loss still to confirm, else what the community was on the previous pass. */
export function changeNote(community) {
  if (isUnconfirmedLoss(community)) return "route lost, to confirm";
  if (community.changed && community.previousStatus && STATUSES[community.previousStatus]) {
    return `was ${STATUSES[community.previousStatus].label.toLowerCase()}`;
  }
  return "";
}

/** The filter pills; `onFilter(id)` hears every click. */
export function renderListFilters(container, active, onFilter) {
  container.innerHTML = FILTERS.map(
    ({ id, label }) =>
      `<button type="button" class="pill" data-filter="${id}" aria-pressed="${id === active}">${label}</button>`,
  ).join("");
  container
    .querySelectorAll("[data-filter]")
    .forEach((button) => button.addEventListener("click", () => onFilter(button.dataset.filter)));
}

/**
 * Renders a row per community. `classesM` are the area's width classes and `dayLabel` names the pass's day in the
 * empty message. `onSelect(id)` hears every click.
 */
export function renderCommunityList(container, { communities, selectedId, classesM = [], dayLabel = "" }, onSelect) {
  container.replaceChildren();
  if (communities.length === 0) {
    const empty = document.createElement("p");
    empty.className = "list-empty";
    empty.textContent = `No community matches this filter${dayLabel ? ` on ${dayLabel}` : ""}.`;
    container.append(empty);
    return;
  }
  for (const community of communities) {
    const status = statusOf(community);
    const selected = community.id === selectedId;
    const button = document.createElement("button");
    button.type = "button";
    button.className = `site-row${selected ? " is-selected" : ""}`;
    button.dataset.id = community.id;
    button.setAttribute("aria-pressed", String(selected));
    const note = changeNote(community);
    button.innerHTML = `${glyph(status, 14)}
      <span class="site-main"><strong>${escapeHtml(community.name)}</strong><small>${escapeHtml(KIND_LABELS[community.kind] ?? "Community")}</small></span>
      <span class="site-route"><b>${escapeHtml(widthClassLabel(community.latest?.routeWidthM ?? null, status, classesM, community.latest?.reason))}</b>${
        note ? `<small>${escapeHtml(note)}</small>` : ""
      }</span>
      <span class="visually-hidden">${STATUSES[status].label}</span>`;
    button.addEventListener("click", () => onSelect(community.id));
    container.append(button);
  }
}
