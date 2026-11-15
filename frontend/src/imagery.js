// What the map draws over the basemap: one radar image at a time, to compare by switching, under the analysis
// overlays (water, change...). The layers come from the API (services/api.js); this only decides which to show.

/**
 * The radar images, by the layer id the API serves them under. NISAR is `radar_hh` (CONTRACTS.md); the Sentinel-1
 * cross-check image is expected as `sentinel1_vv`, and its button stays disabled until a pass serves it.
 */
export const RADAR_SOURCES = [
  { id: "radar_hh", label: "NISAR" },
  { id: "sentinel1_vv", label: "Sentinel-1" },
];

const RADAR_IDS = new Set(RADAR_SOURCES.map((source) => source.id));

/** The layers of a pass that aren't a radar image, in the API's order. */
export function overlaysOf(imagery) {
  return imagery.filter((layer) => !RADAR_IDS.has(layer.id));
}

/**
 * The imagery choices for a pass: `radar` (a radar layer id, or null for none), its `opacity` and which `overlays`
 * show. A new pass keeps what the user chose on the previous one; the rest follows the catalog's defaults.
 */
export function imageryChoices(imagery, previous = null) {
  const defaultRadar = imagery.find((layer) => RADAR_IDS.has(layer.id) && layer.defaultVisible);
  const radar = previous ? previous.radar : (defaultRadar?.id ?? null);
  const opacity = previous ? previous.opacity : (defaultRadar?.defaultOpacity ?? 1);
  const overlays = Object.fromEntries(
    overlaysOf(imagery).map((layer) => [layer.id, previous?.overlays?.[layer.id] ?? layer.defaultVisible]),
  );
  return { radar, opacity, overlays };
}

/**
 * The images to draw, bottom to top: the chosen radar, if the pass serves it, then every overlay turned on.
 * Each keeps its catalog opacity, except the radar, whose opacity the user sets.
 */
export function layersToShow(choices, imagery) {
  const shown = [];
  const radar = imagery.find((layer) => layer.id === choices.radar);
  if (radar) {
    shown.push({
      id: radar.id,
      url: radar.url,
      bounds: radar.bounds,
      widthPx: radar.widthPx ?? null,
      opacity: choices.opacity,
    });
  }
  for (const layer of overlaysOf(imagery)) {
    if (choices.overlays[layer.id]) {
      shown.push({
        id: layer.id,
        url: layer.url,
        bounds: layer.bounds,
        widthPx: layer.widthPx ?? null,
        opacity: layer.defaultOpacity,
      });
    }
  }
  return shown;
}
