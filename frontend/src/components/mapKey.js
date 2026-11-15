// The map key: the shape and colour of every status, as the markers draw them, and the hub.
import { STATUSES, STATUS_ORDER } from "../status.js";
import { glyph } from "./glyph.js";

/** Renders the key; `hubName` names the hub's white diamond. */
export function renderMapKey(container, hubName) {
  const statuses = STATUS_ORDER.map(
    (status) => `<li>${glyph(status, 11)}<span>${STATUSES[status].label}</span></li>`,
  ).join("");
  container.innerHTML = `<ul class="map-key-list">${statuses}<li><i class="key-hub" aria-hidden="true"></i><span>${
    hubName ? `${hubName}, the hub` : "Hub"
  }</span></li></ul>`;
}
