import { formatDay } from "../format.js";
import { RADAR_SOURCES, overlaysOf } from "../imagery.js";

const escape = (text) =>
  String(text).replace(
    /[&<>"']/g,
    (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char],
  );

/** How the pass selector names a pass: its date, platform and track, and whether it could be judged. */
export function passLabel(pass) {
  const track = pass.track == null ? "" : ` · T${pass.track}`;
  const judged = pass.evaluated ? "" : " (not evaluated)";
  return `${formatDay(pass.acquiredOn)} · ${pass.platform}${track}${judged}`;
}

/** What the panel says under the controls: why there is nothing to show, or what to keep in mind. */
export function imageryNote({ live, imagery, pass, failed = false }) {
  if (failed) return "This pass could not be loaded from the monitor: the map still shows the previous one.";
  if (!live) return "Demo data: the radar images come with the live monitor.";
  if (pass && !pass.evaluated) {
    const share = pass.coverage == null ? "too little" : `only ${Math.round(pass.coverage * 100)} %`;
    return `This pass saw ${share} of the area: not enough to judge the routes.`;
  }
  if (imagery.length === 0) return "The monitor serves no images for this pass yet.";
  return "";
}

function segmented(name, label, options, selected) {
  const buttons = options
    .map(
      ({ value, text, disabled = false, note = "" }) =>
        `<button type="button" data-${name}="${escape(value)}" aria-pressed="${value === selected}"${
          disabled ? " disabled" : ""
        }${note ? ` title="${escape(note)}"` : ""}>${escape(text)}</button>`,
    )
    .join("");
  return `<div class="control-field" role="group" aria-labelledby="control-${name}"><span class="control-label" id="control-${name}">${label}</span><div class="segmented">${buttons}</div></div>`;
}

/**
 * The layers panel: the pass, the basemap, the radar image (one at a time, to compare by switching) with its
 * opacity, and the analysis overlays. `unavailable` holds the layer ids whose image failed to load.
 * Handlers: `onPass(id)`, `onBasemap("map" | "satellite")`, `onRadar(id | null)`, `onOpacity(0..1)`,
 * `onOverlay(id, visible)`.
 */
export function renderLayerControls(container, state, handlers) {
  const { passes, passId, basemap, imagery, choices, unavailable, live, failed = false } = state;
  const offered = new Set(imagery.map((layer) => layer.id));
  const pass = passes.find((item) => item.id === passId) ?? null;

  const passOptions = passes
    .map(
      (item) =>
        `<option value="${escape(item.id)}"${item.id === passId ? " selected" : ""}>${escape(passLabel(item))}</option>`,
    )
    .join("");
  const radarOptions = [
    { value: "", text: "Off" },
    ...RADAR_SOURCES.map(({ id, label }) => {
      const missing = !offered.has(id);
      const failed = unavailable.has(id);
      return {
        value: id,
        text: label,
        disabled: missing,
        note: missing ? "Not served for this pass" : failed ? "The image could not be loaded" : "",
      };
    }),
  ];
  const overlays = overlaysOf(imagery)
    .map(
      (layer) =>
        `<label class="control-check"><input type="checkbox" data-overlay="${escape(layer.id)}"${
          choices.overlays[layer.id] ? " checked" : ""
        } /><span>${escape(layer.label)}</span>${
          unavailable.has(layer.id) ? '<small class="control-warning">unavailable</small>' : ""
        }</label>`,
    )
    .join("");
  const radarFailed = choices.radar && unavailable.has(choices.radar);
  const note = imageryNote({ live, imagery, pass, failed });
  const percent = Math.round(choices.opacity * 100);

  container.innerHTML = `
    ${passes.length > 0 ? `<label class="control-field"><span class="control-label">Pass</span><select class="control-select" data-pass>${passOptions}</select></label>` : ""}
    ${segmented(
      "basemap",
      "Basemap",
      [
        { value: "map", text: "Map" },
        { value: "satellite", text: "Satellite" },
      ],
      basemap,
    )}
    ${segmented("radar", "Radar", radarOptions, choices.radar ?? "")}
    <label class="control-range"><span class="control-label">Radar opacity</span><input type="range" min="0" max="100" step="5" value="${percent}" data-opacity${
      choices.radar && offered.has(choices.radar) ? "" : " disabled"
    } /><output>${percent} %</output></label>
    ${radarFailed ? '<p class="control-warning">The radar image could not be loaded.</p>' : ""}
    ${overlays ? `<fieldset class="control-field"><legend class="control-label">Overlays</legend>${overlays}</fieldset>` : ""}
    ${note ? `<p class="control-note">${escape(note)}</p>` : ""}`;

  container.querySelector("[data-pass]")?.addEventListener("change", (event) => handlers.onPass(event.target.value));
  container
    .querySelectorAll("[data-basemap]")
    .forEach((button) => button.addEventListener("click", () => handlers.onBasemap(button.dataset.basemap)));
  container
    .querySelectorAll("[data-radar]")
    .forEach((button) => button.addEventListener("click", () => handlers.onRadar(button.dataset.radar || null)));
  const slider = container.querySelector("[data-opacity]");
  slider.addEventListener("input", () => {
    slider.nextElementSibling.textContent = `${slider.value} %`;
    handlers.onOpacity(Number(slider.value) / 100);
  });
  container
    .querySelectorAll("[data-overlay]")
    .forEach((box) => box.addEventListener("change", () => handlers.onOverlay(box.dataset.overlay, box.checked)));
}
