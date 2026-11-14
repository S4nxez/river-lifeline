import { STATUSES, countByStatus } from "../status.js";

/** Severity order: the statuses that need attention first. */
const ORDER = Object.keys(STATUSES).sort((a, b) => STATUSES[a].severity - STATUSES[b].severity);

/**
 * The map's legend, which also filters it: one toggle per status, with how many communities are in it.
 * `visible` is the set of statuses shown; `onToggle(status)` hears every click.
 */
export function renderStatusFilter(container, communities, visible, onToggle) {
  const counts = countByStatus(communities);
  container.replaceChildren();
  for (const status of ORDER) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "filter-chip";
    button.dataset.status = status;
    button.setAttribute("aria-pressed", String(visible.has(status)));
    button.innerHTML = `<i class="key-dot status-bg-${status}"></i><span>${STATUSES[status].label}</span><b>${counts[status]}</b>`;
    button.addEventListener("click", () => onToggle(status));
    container.append(button);
  }
}

/** The statuses shown after toggling `status`; turning the last one off shows them all again. */
export function toggleStatus(visible, status) {
  const next = new Set(visible);
  if (next.has(status)) next.delete(status);
  else next.add(status);
  return next.size === 0 ? new Set(ORDER) : next;
}
