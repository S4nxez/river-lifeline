import { glyph } from "../components/glyph.js";
import { escapeHtml, widthClassLabel } from "../format.js";
import { STATUSES, statusOf } from "../status.js";

const FALLBACK_ZOOM = 9;
const COMMUNITY_ZOOM = 12;
const HUB_SIZE = 16;
// Metres per pixel of a 256 px tile at zoom 0 on the equator
const EQUATOR_METRES_PER_PIXEL = 156_543.03;

// CARTO basemaps: Voyager (day) and Dark Matter (night). Keyless tiles are watermarked "API key required". The key
// is public by design (every tile URL carries it): what protects it is the CARTO dashboard, which restricts it to our
// domains, to basemaps and to a quota.
const CARTO_API_KEY = "cb1_58s2_1_e8ce9388cc9827c921fc7005";
const CARTO_STYLES = { light: "rastertiles/voyager", dark: "dark_all" };
const CARTO_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> &copy; <a href="https://carto.com/attributions">CARTO</a>';

// When CARTO refuses the key (a domain it isn't allowed on, like localhost), OpenStreetMap's own tiles keep the map
// usable. They only come in a day style: CSS darkens them in the dark theme (.map-fallback in components.css).
const OSM_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
const OSM_ATTRIBUTION = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';

// The satellite basemap: EOX's cloudless Sentinel-2 mosaic, a photo to read the radar against. Free with attribution
// for non-commercial use. Like the map tiles, it is a basemap, not data: the monitor's own images come from the API.
export const SATELLITE_URL = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2023_3857/default/g/{z}/{y}/{x}.jpg";
const SATELLITE_ATTRIBUTION =
  '<a href="https://s2maps.eu">Sentinel-2 cloudless</a> by <a href="https://eox.at">EOX</a> (Copernicus Sentinel data 2023)';

// The monitor's images sit between the basemap (tile pane, 200) and the markers (overlay pane, 400)
const IMAGERY_PANE = "imagery";

let map;
let baseTiles;
let basemap = "map";
let theme = "dark";
let cartoRefused = false;
let hubLayer;
let markerLayer;
let imageryLayer;
let visibleIds = null;
let selectedId = null;
let widthClassesM = [];
let onImageError = () => {};
const markers = new Map();
const images = new Map();

/** The CARTO tile URL of the day ("light") or night ("dark") map, with the API key. */
export function tileUrl(theme) {
  const style = CARTO_STYLES[theme] ?? CARTO_STYLES.light;
  return `https://{s}.basemaps.cartocdn.com/${style}/{z}/{x}/{y}{r}.png?key=${CARTO_API_KEY}`;
}

/**
 * A community glyph's size (px) at a zoom level: small when zoomed out, so neighbouring communities don't cover each
 * other, and full size (14 px) from zoom 12 in.
 */
export function markerSize(zoom) {
  return Math.max(6, Math.min(14, (Math.round(zoom) - 5) * 2));
}

/** Whether the user asked for less motion: then the map jumps instead of flying. */
const prefersLessMotion = () => globalThis.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches ?? false;

/** A community's marker icon: its status glyph with a thin white outline, ringed when selected. */
function iconFor(status, size, selected) {
  return L.divIcon({
    className: `glyph-marker${selected ? " is-selected" : ""}`,
    html: glyph(status, size, "glyph-outlined"),
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],
  });
}

/** Markers stack by severity: isolated above at risk above connected; the selected one above all. */
const zIndexFor = (status, selected) => (selected ? 10_000 : (4 - STATUSES[status].severity) * 1_000);

/** What a community's tooltip says: its name and status, and its route's narrowest point when it has one. */
export function tooltipFor(community, classesM = []) {
  const status = statusOf(community);
  const width = community.latest?.routeWidthM;
  const route =
    width == null
      ? ""
      : `<br>Narrowest point ${escapeHtml(widthClassLabel(width, status, classesM, community.latest?.reason))}`;
  return `<b>${escapeHtml(community.name)}</b> · ${STATUSES[status].label}${route}`;
}

/**
 * Draws the area, its hub and every community, by status. Returns the controls the page drives it with.
 * `onImageError(layerId)` hears of every image the API fails to serve.
 */
export async function createMap({
  area,
  hub,
  communities,
  onSelect,
  theme: initialTheme = "dark",
  onImageError: report,
}) {
  // A new map starts from nothing: no tiles, images or markers of a previous one
  theme = initialTheme;
  onImageError = report ?? (() => {});
  baseTiles = null;
  basemap = "map";
  cartoRefused = false;
  visibleIds = null;
  selectedId = null;
  widthClassesM = area?.widthClassesM ?? [];
  markers.clear();
  images.clear();
  map = L.map("map", { zoomControl: false, scrollWheelZoom: true, zoomSnap: 0.25 });
  frame(area, hub, communities);
  L.control.zoom({ position: "bottomleft" }).addTo(map);
  map.createPane(IMAGERY_PANE).style.zIndex = 300;
  drawBasemap();
  imageryLayer = L.layerGroup().addTo(map);
  hubLayer = L.layerGroup().addTo(map);
  markerLayer = L.layerGroup().addTo(map);

  // The watched area, like the footprint of a sensor
  if (area?.center && area.radiusKm) {
    L.circle([area.center.lat, area.center.lng], {
      radius: area.radiusKm * 1000,
      className: "aoi-footprint",
      fill: false,
      weight: 1,
      dashArray: "2 6",
      interactive: false,
    }).addTo(hubLayer);
  }
  if (hub) {
    L.marker([hub.lat, hub.lng], {
      icon: L.divIcon({
        className: "hub-marker",
        html: '<span class="hub-diamond"></span>',
        iconSize: [HUB_SIZE, HUB_SIZE],
        iconAnchor: [HUB_SIZE / 2, HUB_SIZE / 2],
      }),
      zIndexOffset: 20_000,
      keyboard: false,
    })
      .bindTooltip(`<b>${escapeHtml(hub.name)}</b><br>Hub town: routes are traced to here`, {
        direction: "top",
        offset: [0, -HUB_SIZE / 2],
        className: "map-tip",
      })
      .addTo(hubLayer);
  }
  setCommunities(communities, onSelect);
  map.on("zoomend", onZoom);

  await new Promise((resolve) => setTimeout(resolve, 80));
  map.invalidateSize();
  // Framed again now the container has its final size: framed before, the area sat off centre
  frame(area, hub, communities);
  return {
    selectCommunity,
    setTheme,
    setBasemap,
    setCommunities: (next) => setCommunities(next, onSelect),
    setVisibleIds,
    showImagery,
  };
}

/** After a zoom: glyphs to the new size, and images crisp once one image pixel covers a screen pixel or more. */
function onZoom() {
  const size = markerSize(map.getZoom());
  for (const [id, entry] of markers) entry.marker.setIcon(iconFor(entry.status, size, id === selectedId));
  updateSharpness();
}

/** Metres per screen pixel at the map's centre and zoom. */
function metresPerScreenPixel() {
  const latitude = map.getCenter?.()?.lat ?? 0;
  return (EQUATOR_METRES_PER_PIXEL * Math.cos((latitude * Math.PI) / 180)) / 2 ** map.getZoom();
}

/** Whether the images on the map should be drawn pixel by pixel: their finest pixel is now at least a screen pixel. */
export function imagesAreSharp(metresPerScreen, imagePixelsMetres) {
  return imagePixelsMetres.length > 0 && metresPerScreen <= Math.min(...imagePixelsMetres);
}

function updateSharpness() {
  const pixels = [...images.values()].map((entry) => entry.pixelMetres).filter((metres) => metres > 0);
  map.getContainer().classList.toggle("imagery-sharp", imagesAreSharp(metresPerScreenPixel(), pixels));
}

/** Frames the area: its bounds, else every place on it, else its centre. */
function frame(area, hub, communities) {
  if (area?.bounds) {
    map.fitBounds(area.bounds);
    return;
  }
  const places = [hub, ...communities].filter(Boolean).map((place) => [place.lat, place.lng]);
  if (places.length > 1) map.fitBounds(places, { padding: [40, 40] });
  else if (places.length === 1) map.setView(places[0], FALLBACK_ZOOM);
  else if (area?.center) map.setView([area.center.lat, area.center.lng], FALLBACK_ZOOM);
}

/** Replaces the basemap tiles with the ones the basemap choice, the theme and CARTO's answer call for. */
function drawBasemap() {
  if (baseTiles) map.removeLayer(baseTiles);
  if (basemap === "satellite") {
    baseTiles = L.tileLayer(SATELLITE_URL, { attribution: SATELLITE_ATTRIBUTION, maxNativeZoom: 15, maxZoom: 18 });
  } else if (cartoRefused) {
    baseTiles = L.tileLayer(OSM_URL, { attribution: OSM_ATTRIBUTION, maxZoom: 19 });
  } else {
    baseTiles = L.tileLayer(tileUrl(theme), { attribution: CARTO_ATTRIBUTION, subdomains: "abcd", maxZoom: 18 });
    baseTiles.on("tileerror", useOpenStreetMap);
  }
  baseTiles.addTo(map);
  // Only the OpenStreetMap fallback needs darkening at night: the satellite photo stays as it is
  map.getContainer().classList.toggle("map-fallback", basemap === "map" && cartoRefused);
}

/** "map" (CARTO, or OpenStreetMap where CARTO refuses the key) or "satellite". */
function setBasemap(next) {
  if (next === basemap) return;
  basemap = next;
  drawBasemap();
}

/** Switches the map basemap to the day ("light") or night ("dark") style. */
function setTheme(next) {
  theme = next;
  if (basemap === "map" && !cartoRefused) baseTiles.setUrl(tileUrl(theme));
}

/** CARTO refused a tile: use OpenStreetMap for good, instead of leaving the map without a background. */
function useOpenStreetMap() {
  if (cartoRefused) return;
  cartoRefused = true;
  console.warn("CARTO refused the basemap tiles (is this domain allowed for the key?): using OpenStreetMap instead.");
  if (basemap === "map") drawBasemap();
}

/** Draws the communities of a pass, replacing the previous pass's. */
function setCommunities(communities, onSelect) {
  markers.clear();
  const size = markerSize(map.getZoom());
  communities.forEach((community) => {
    const status = statusOf(community);
    const selected = community.id === selectedId;
    const marker = L.marker([community.lat, community.lng], {
      icon: iconFor(status, size, selected),
      zIndexOffset: zIndexFor(status, selected),
      title: `${community.name} · ${STATUSES[status].label}`,
      riseOnHover: true,
    }).bindTooltip(tooltipFor(community, widthClassesM), {
      direction: "top",
      offset: [0, -size / 2],
      className: "map-tip",
    });
    marker.on("click", () => onSelect(community.id));
    markers.set(community.id, { marker, status });
  });
  drawMarkers();
}

/** Shows only the communities in `ids` (null: all of them). */
function setVisibleIds(ids) {
  visibleIds = ids ? new Set(ids) : null;
  drawMarkers();
}

function drawMarkers() {
  markerLayer.clearLayers();
  for (const [id, { marker }] of markers) {
    if (!visibleIds || visibleIds.has(id)) marker.addTo(markerLayer);
  }
}

/** Rings the community `id` (null: none) and, with `fly`, flies to it. */
function selectCommunity(id, { fly = true } = {}) {
  const size = markerSize(map.getZoom());
  for (const changed of [selectedId, id]) {
    const entry = changed && markers.get(changed);
    if (!entry) continue;
    entry.marker.setIcon(iconFor(entry.status, size, changed === id));
    entry.marker.setZIndexOffset(zIndexFor(entry.status, changed === id));
  }
  selectedId = id;
  const entry = id && markers.get(id);
  if (!entry || !fly) return;
  const zoom = Math.max(map.getZoom(), COMMUNITY_ZOOM);
  if (prefersLessMotion()) map.setView(entry.marker.getLatLng(), zoom);
  else map.flyTo(entry.marker.getLatLng(), zoom, { duration: 0.6 });
  if (!visibleIds || visibleIds.has(id)) entry.marker.openTooltip();
}

/** Metres covered by one pixel of an image across its bounds (`[[south, west], [north, east]]`), or 0 if unknown. */
function pixelMetresOf(layer) {
  if (!layer.widthPx) return 0;
  const [[south, west], [north, east]] = layer.bounds;
  const latitude = (south + north) / 2;
  const metres = (east - west) * 111_320 * Math.cos((latitude * Math.PI) / 180);
  return metres / layer.widthPx;
}

/**
 * Draws exactly `layers` ({ id, url, bounds, opacity, widthPx }, bottom to top) over the basemap. An image already on
 * the map is kept and only its opacity changes, so dragging the opacity slider never reloads it.
 */
function showImagery(layers) {
  const wanted = new Set(layers.map((layer) => layer.url));
  for (const [url, entry] of images) {
    if (!wanted.has(url)) {
      imageryLayer.removeLayer(entry.image);
      images.delete(url);
    }
  }
  for (const layer of layers) {
    let entry = images.get(layer.url);
    if (entry) {
      entry.image.setOpacity(layer.opacity);
    } else {
      const image = L.imageOverlay(layer.url, layer.bounds, {
        opacity: layer.opacity,
        pane: IMAGERY_PANE,
        alt: layer.id,
      });
      image.on("error", () => onImageError(layer.id));
      image.addTo(imageryLayer);
      entry = { image, pixelMetres: pixelMetresOf(layer) };
      images.set(layer.url, entry);
    }
    entry.image.bringToFront();
  }
  updateSharpness();
}
