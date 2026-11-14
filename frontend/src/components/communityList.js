import { formatDay, formatWidth } from "../format.js";
import { KIND_LABELS, STATUSES, statusOf } from "../status.js";

export function renderCommunityList(container, communities, selectedId, onSelect) {
  container.replaceChildren();
  communities.forEach((community, index) => {
    const status = statusOf(community);
    const button = document.createElement("button");
    button.type = "button";
    button.className = `site-item ${community.id === selectedId ? "is-selected" : ""}`;
    button.setAttribute("aria-pressed", String(community.id === selectedId));
    button.innerHTML = `
      <span class="site-index">${String(index + 1).padStart(2, "0")}</span>
      <span class="site-main"><strong>${community.name}</strong><small>${KIND_LABELS[community.kind]}</small></span>
      <span class="site-change"><b class="status-${status}">${STATUSES[status].label}</b><small>${formatWidth(community.latest?.routeWidthM)}</small></span>`;
    button.addEventListener("click", () => onSelect(community.id));
    container.append(button);
  });
}

export function renderSelectedSummary(container, community) {
  const latest = community.latest;
  const seen = latest ? `Last seen by NISAR on ${formatDay(latest.acquiredOn)}` : "Not assessed by any pass yet";
  container.innerHTML = `<div class="summary-top"><span>SELECTED COMMUNITY</span><span class="confidence">${KIND_LABELS[community.kind]}</span></div>
    <h3>${community.name}</h3><p>${seen}.</p>`;
}
