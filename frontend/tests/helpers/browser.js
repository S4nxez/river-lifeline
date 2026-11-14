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
  const recorded = { mapContainer: null, tileLayers: [], markers: [], flights: [], invalidated: false };
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
      recorded.tileLayers.push({ url, options });
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
 * A community as `GET /api/communities` describes it. `latest` overrides merge into its latest assessment;
 * `latest: null` is a community no pass assessed yet.
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

/** An assessment as the API's community history lists it. */
export function assessment(acquiredOn, status, routeWidthM = null) {
  return { passId: `nisar-${acquiredOn}`, acquiredOn, status, routeWidthM, sentinel1: null };
}

/** Resolves once `condition()` is true, failing after `timeoutMs`. */
export async function waitFor(condition, timeoutMs = 2000) {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline) throw new Error("waitFor: condition not met in time");
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}
