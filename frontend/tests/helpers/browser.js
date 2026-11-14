// Test doubles for what the browser and the CDN scripts in index.html provide: the DOM (jsdom),
// Leaflet's global `L` and Chart.js's global `Chart`. Each test file runs in its own process,
// so installing globals here never leaks between files.
import { JSDOM } from "jsdom";

/** Installs a jsdom document as the global `window`/`document` and returns the document. */
export function installDom(body = "") {
  const dom = new JSDOM(`<!doctype html><html><body>${body}</body></html>`, { url: "http://localhost/" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  return dom.window.document;
}

/** Installs a fake `Chart` that records every chart created; returns the list of instances. */
export function installChart() {
  const instances = [];
  globalThis.Chart = class FakeChart {
    constructor(canvas, config) {
      this.canvas = canvas;
      this.config = config;
      this.destroyed = false;
      instances.push(this);
    }

    destroy() {
      this.destroyed = true;
    }
  };
  return instances;
}

/** Installs a fake Leaflet `L` and returns what the app did with it (markers, map moves). */
export function installLeaflet({ zoom = 2 } = {}) {
  const recorded = { mapContainer: null, tiles: null, markers: [], flights: [], invalidated: false };
  const map = {
    setView: () => map,
    invalidateSize: () => {
      recorded.invalidated = true;
    },
    getZoom: () => zoom,
    flyTo: (latLng, targetZoom) => recorded.flights.push({ latLng, zoom: targetZoom }),
  };
  const addable = { addTo: () => addable };
  globalThis.L = {
    map: (container) => {
      recorded.mapContainer = container;
      return map;
    },
    tileLayer: (url, options) => {
      recorded.tiles = { url, options };
      return addable;
    },
    layerGroup: () => addable,
    circleMarker: (latLng, options) => {
      const marker = {
        latLng,
        options,
        tooltip: null,
        tooltipOpen: false,
        handlers: {},
        bindTooltip(text) {
          this.tooltip = text;
          return this;
        },
        on(event, handler) {
          this.handlers[event] = handler;
          return this;
        },
        addTo() {
          return this;
        },
        getLatLng() {
          return latLng;
        },
        openTooltip() {
          this.tooltipOpen = true;
        },
      };
      recorded.markers.push(marker);
      return marker;
    },
  };
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
export function assessment(acquiredOn, status, routeWidthM = null) {
  return { passId: `nisar-${acquiredOn}`, acquiredOn, status, routeWidthM, sentinel1: null };
}

/** A feature of `GET /api/v1/aois/{aoi}/passes/{pass}/communities`: the geometry is the boarding point. */
export function apiFeature({ id = "uarini", name = "Uarini", kind = "town", status = "at_risk", width = 40 } = {}) {
  return {
    type: "Feature",
    id,
    geometry: { type: "Point", coordinates: [-65.113, -2.99] },
    properties: { id, name, kind, status, min_width_class_m: width, village_location: [-65.114, -2.991] },
  };
}

/** The bodies of the contract endpoints the overview is built from, for a pass acquired on `day`. */
export function apiOverview(features, day = "2026-09-09") {
  return {
    aoi: { id: "tefe", name: "Tefé", destination: { name: "Port of Tefé", location: [-64.711, -3.354] } },
    summary: {
      pass_id: `nisar-${day}`,
      acquired_at: `${day}T09:45:12Z`,
      river_level_m: 9.8,
      counts: { connected: 1, at_risk: 1, isolated: 2, no_data: 0 },
    },
    collection: { type: "FeatureCollection", pass_id: `nisar-${day}`, acquired_at: `${day}T09:45:12Z`, features },
  };
}

/** A history entry of `GET /api/v1/aois/{aoi}/communities/{id}`. */
export function apiHistoryEntry(day, status, width = null) {
  return { pass_id: `nisar-${day}`, acquired_at: `${day}T09:45:12Z`, status, min_width_class_m: width };
}

/** Resolves once `condition()` is true, failing after `timeoutMs`. */
export async function waitFor(condition, timeoutMs = 2000) {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline) throw new Error("waitFor: condition not met in time");
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}
