// The caption a mission puts on every image it publishes: what the radar image on the map is, and how good.
import { escapeHtml, formatDay, formatUtcTime } from "../format.js";

const ARROWS = { ascending: "↑", descending: "↓" };

/** The plate's text for `plate` (the pass's image card) when `radar` (a layer id, or null) is on the map. */
export function plateText(plate, radar) {
  if (!plate) return null;
  const day = plate.acquiredAt ? formatDay(plate.acquiredAt.slice(0, 10)) : null;
  if (radar === "sentinel1_vv") {
    return {
      platform: "Sentinel-1",
      parts: ["C-band VV", day ? `cross-check of the NISAR pass of ${day}` : "cross-check image"],
    };
  }
  const parts = [];
  const polarization = plate.polarizations?.includes("HH") ? " HH" : "";
  if (plate.band) parts.push(`${plate.band}-band${polarization}`);
  if (plate.track != null) {
    const arrow = ARROWS[plate.orbitDirection] ? ` ${ARROWS[plate.orbitDirection]} ${plate.orbitDirection}` : "";
    parts.push(`Track ${plate.track}${arrow}`);
  }
  if (day) parts.push([day, formatUtcTime(plate.acquiredAt)].filter(Boolean).join(" "));
  if (plate.pixelSizeM != null) parts.push(`${plate.pixelSizeM} m grid`);
  if (plate.coverage != null) parts.push(`${Math.round(plate.coverage * 100)} % of the area`);
  if (plate.product && /beta/i.test(plate.product)) parts.push("beta product");
  return { platform: plate.platform ?? "NISAR", parts };
}

/** Renders the plate, or hides it when there is no image card. */
export function renderPlate(container, plate, radar) {
  const text = plateText(plate, radar);
  container.hidden = !text;
  if (!text) return;
  container.innerHTML = `<b>${escapeHtml(text.platform)}</b>${text.parts.map((part) => ` · ${escapeHtml(part)}`).join("")}`;
}
