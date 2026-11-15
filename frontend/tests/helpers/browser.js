// Test doubles for what the browser and the CDN scripts in index.html provide: the DOM (jsdom) and Leaflet's
// global `L`. Each test file runs in its own process, so installing globals here never leaks between files.
import { JSDOM } from "jsdom";

/** Installs a jsdom document as the global `window`/`document` and returns the document. */
export function installDom(body = "") {
  const dom = new JSDOM(`<!doctype html><html><body>${body}</body></html>`, { url: "http://localhost/" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  return dom.window.document;
}

/**
 * Installs a fake Leaflet `L` and returns what the app did with it: the map's framing and moves, every tile layer
 * (the current one last), every marker, every image overlay and which ones are on the map.
 */
export function installLeaflet({ zoom = 2 } = {}) {
  const recorded = {
    mapHandlers: {},
    mapContainer: null,
    view: null,
    tiles: [],
    containerClasses: new Set(),
    markers: [],
    images: [],
    panes: {},
    zoomControl: null,
    flights: [],
    circles: [],
    hub: null,
    invalidated: false,
  };
  const map = {
    setView: (center, viewZoom) => {
      recorded.view = { center, zoom: viewZoom };
      return map;
    },
    fitBounds: (bounds, options) => {
      recorded.view = { bounds, options };
      return map;
    },
    invalidateSize: () => {
      recorded.invalidated = true;
    },
    getZoom: () => zoom,
    // Moves the fake map to another zoom and fires "zoomend", as Leaflet does after a zoom
    zoomTo: (next) => {
      zoom = next;
      recorded.mapHandlers.zoomend?.();
    },
    on: (event, handler) => {
      recorded.mapHandlers[event] = handler;
      return map;
    },
    flyTo: (latLng, targetZoom) => recorded.flights.push({ latLng, zoom: targetZoom }),
    getCenter: () => ({ lat: -3.4, lng: -64.75 }),
    removeLayer: (layer) => {
      layer.removed = true;
    },
    createPane: (name) => {
      recorded.panes[name] = { style: {} };
      return recorded.panes[name];
    },
    getContainer: () => ({
      classList: {
        toggle: (name, on) => (on ? recorded.containerClasses.add(name) : recorded.containerClasses.delete(name)),
      },
    }),
  };

  /** A layer group that knows what is on it. */
  function group() {
    const layers = new Set();
    return {
      layers,
      addTo() {
        return this;
      },
      addLayer(layer) {
        layers.add(layer);
      },
      removeLayer(layer) {
        layers.delete(layer);
        layer.removed = true;
      },
      clearLayers() {
        layers.clear();
      },
    };
  }

  /** What every layer can do: be added to the map or to a group, and take event handlers. */
  const layer = (fields) => ({
    ...fields,
    handlers: {},
    removed: false,
    on(event, handler) {
      this.handlers[event] = handler;
      return this;
    },
    addTo(target) {
      target?.addLayer?.(this);
      return this;
    },
  });

  globalThis.L = {
    map: (container) => {
      recorded.mapContainer = container;
      return map;
    },
    control: {
      zoom: (options) => {
        recorded.zoomControl = options;
        return { addTo: () => {} };
      },
    },
    tileLayer: (url, options) => {
      const tiles = layer({
        url,
        options,
        setUrl(next) {
          this.url = next;
          return this;
        },
      });
      recorded.tiles.push(tiles);
      return tiles;
    },
    layerGroup: group,
    imageOverlay: (url, bounds, options) => {
      const image = layer({
        url,
        bounds,
        options,
        opacity: options.opacity,
        frontCount: 0,
        setOpacity(opacity) {
          this.opacity = opacity;
          return this;
        },
        bringToFront() {
          this.frontCount += 1;
          return this;
        },
      });
      recorded.images.push(image);
      return image;
    },
    divIcon: (options) => ({ ...options }),
    circle: (latLng, options) => {
      const circle = layer({ latLng, options });
      recorded.circles.push(circle);
      return circle;
    },
    marker: (latLng, options) => {
      const marker = layer({
        latLng,
        options,
        tooltip: null,
        tooltipOptions: null,
        tooltipOpen: false,
        bindTooltip(text, tooltipOptions) {
          this.tooltip = text;
          this.tooltipOptions = tooltipOptions;
          return this;
        },
        getLatLng() {
          return latLng;
        },
        setIcon(icon) {
          this.options = { ...this.options, icon };
          return this;
        },
        setZIndexOffset(offset) {
          this.options = { ...this.options, zIndexOffset: offset };
          return this;
        },
        openTooltip() {
          this.tooltipOpen = true;
        },
      });
      // The hub is the marker with the diamond icon; every other marker is a community
      if (options.icon?.className === "hub-marker") recorded.hub = marker;
      else recorded.markers.push(marker);
      return marker;
    },
  };
  recorded.zoomTo = map.zoomTo;
  return recorded;
}

/**
 * A community in the page's view model (what `loadOverview` returns). `latest` overrides merge into its latest
 * assessment; `latest: null` is a community no pass assessed yet.
 */
export function community({ latest = {}, ...overrides } = {}) {
  const defaults = {
    passId: "nisar-2026-08-28",
    acquiredOn: "2026-08-28",
    status: "at_risk",
    routeWidthM: 40,
    sentinel1: { sceneOn: "2026-08-27", agrees: true },
  };
  return {
    id: "uarini",
    name: "Uarini",
    kind: "town",
    lat: -2.99,
    lng: -65.113,
    latest: latest === null ? null : { ...defaults, ...latest },
    ...overrides,
  };
}

/** An assessment in the page's view model, as `loadHistory` lists it. */
export function assessment(acquiredOn, status, routeWidthM = null, reason = null) {
  return { passId: `nisar-${acquiredOn}`, acquiredOn, status, reason, routeWidthM, sentinel1: null };
}

/** A feature of `GET /api/v1/aois/{aoi}/passes/{pass}/communities`: the geometry is the boarding point. */
export function apiFeature({
  id = "uarini",
  name = "Uarini",
  kind = "town",
  status = "at_risk",
  width = 40,
  reason = null,
  previousStatus = null,
  boardingPoint = null,
} = {}) {
  return {
    type: "Feature",
    id,
    geometry: { type: "Point", coordinates: [-65.113, -2.99] },
    properties: {
      id,
      name,
      kind,
      status,
      reason,
      min_width_class_m: width,
      previous_status: previousStatus,
      changed: previousStatus != null && previousStatus !== status,
      village_location: [-65.114, -2.991],
      boarding_point: boardingPoint,
    },
  };
}

/** The bodies of the contract endpoints the overview is built from, for a pass acquired on `day`. */
export function apiOverview(features, day = "2026-09-09") {
  return {
    aoi: {
      id: "tefe",
      name: "Tefé",
      region: "Amazonas",
      center: [-64.75, -3.4],
      bbox: [-65.2, -3.9, -64.3, -2.9],
      radius_km: 55,
      destination: { name: "Tefé", location: [-64.711, -3.354] },
      river_gauge: { provider: "ANA", station_code: "12900001", station_name: "Tefé-Missões" },
      parameters: { connected_min_width_m: 60, tested_widths_m: [40, 60, 100, 200, 400] },
    },
    summary: {
      pass_id: `nisar-${day}`,
      acquired_at: `${day}T09:45:12Z`,
      river_level_m: 9.8,
      counts: { connected: 1, at_risk: 1, isolated: 2, no_data: 0 },
      catalog_generated_at: "2026-11-15T04:54:27Z",
    },
    collection: { type: "FeatureCollection", pass_id: `nisar-${day}`, acquired_at: `${day}T09:45:12Z`, features },
  };
}

/** `GET /api/v1/meta`: the layer catalog, as the contract defines it. */
export function apiMeta() {
  return {
    layers: [
      { id: "radar_hh", label: "Radar (HH)", default_visible: true, default_opacity: 1 },
      { id: "water", label: "Open water", default_visible: true, default_opacity: 0.8 },
      { id: "change", label: "Change vs previous pass", default_visible: false, default_opacity: 0.9 },
    ],
  };
}

/** An item of `GET /api/v1/aois/{aoi}/passes` (oldest first, as the API lists them). */
export function apiPassItem(
  day,
  {
    evaluated = true,
    coverage = 0.97,
    riverLevelM = 9.8,
    track = 81,
    direction = "descending",
    counts = { monitored: 4, connected: 1, at_risk: 1, isolated: 2, no_data: 0, changed: 1 },
    layerIds = undefined,
  } = {},
) {
  return {
    ...(layerIds ? { layer_ids: layerIds } : {}),
    id: `nisar-${day}`,
    acquired_at: `${day}T09:45:12Z`,
    platform: "NISAR",
    track,
    orbit_direction: direction,
    evaluated,
    coverage_fraction: coverage,
    river_level_m: riverLevelM,
    counts,
  };
}

/** `GET /api/v1/aois/{aoi}/passes/{pass}`: its layers, each served from the server root as the API gives them. */
export function apiPassDetail(day, layerIds = ["radar_hh", "water", "change"]) {
  return {
    id: `nisar-${day}`,
    acquired_at: `${day}T09:45:12Z`,
    platform: "NISAR",
    coverage_fraction: 0.99,
    image_card: {
      band: "L",
      polarizations: ["HH", "HV"],
      track: 97,
      orbit_direction: "descending",
      acquired_at: `${day}T22:41:00Z`,
      pixel_size_m: 20,
      product: "GCOV",
    },
    layers: layerIds.map((id) => ({
      id,
      url: `/api/v1/aois/tefe/passes/nisar-${day}/layers/${id}.png`,
      bounds: [-65.2, -3.9, -64.3, -2.9],
      width_px: 256,
      height_px: 256,
    })),
  };
}

/** A history entry of `GET /api/v1/aois/{aoi}/communities/{id}`. */
export function apiHistoryEntry(day, status, width = null, reason = null) {
  return { pass_id: `nisar-${day}`, acquired_at: `${day}T09:45:12Z`, status, reason, min_width_class_m: width };
}

/** `GET /api/v1/aois/{aoi}/river-level`: one reading per day, in metres. */
export function apiRiverLevel(
  readings = [
    ["2026-08-28", 11.8],
    ["2026-09-09", 9.8],
  ],
) {
  return {
    provider: "ANA",
    station_name: "Tefé-Missões",
    unit: "m",
    items: readings.map(([date, level_m]) => ({ date, level_m })),
  };
}

/** Resolves once `condition()` is true, failing after `timeoutMs`. */
export async function waitFor(condition, timeoutMs = 2000) {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline) throw new Error("waitFor: condition not met in time");
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}
