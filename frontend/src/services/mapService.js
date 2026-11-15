import { STATUSES, statusOf } from "../status.js";

const FALLBACK_ZOOM = 9;
const COMMUNITY_ZOOM = 11;

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
let theme = "light";
let cartoRefused = false;
let hubLayer;
let markerLayer;
let hubMarker = null;
let imageryLayer;
let visibleStatuses = new Set(Object.keys(STATUSES));
let onImageError = () => {};
const markers = new Map();
const images = new Map();

/** The CARTO tile URL of the day ("light") or night ("dark") map, with the API key. */
export function tileUrl(theme) {
  const style = CARTO_STYLES[theme] ?? CARTO_STYLES.light;
  return `https://{s}.basemaps.cartocdn.com/${style}/{z}/{x}/{y}{r}.png?key=${CARTO_API_KEY}`;
}

/**
 * Draws the area, its hub and every community, coloured by status. Returns the controls the page drives it with.
 * `onImageError(layerId)` hears of every image the API fails to serve.
 */
export async function createMap({
  area,
  hub,
  communities,
  onSelect,
  theme: initialTheme = "light",
  onImageError: report,
}) {
  // A new map starts from nothing: no tiles, images or markers of a previous one
  theme = initialTheme;
  onImageError = report ?? (() => {});
  baseTiles = null;
  basemap = "map";
  cartoRefused = false;
  visibleStatuses = new Set(Object.keys(STATUSES));
  markers.clear();
  images.clear();
  hubMarker = null;
  map = L.map("map", { zoomControl: false, scrollWheelZoom: false });
  frame(area, hub, communities);
  L.control.zoom({ position: "bottomright" }).addTo(map);
  map.createPane(IMAGERY_PANE).style.zIndex = 300;
  drawBasemap();
  imageryLayer = L.layerGroup().addTo(map);
  hubLayer = L.layerGroup().addTo(map);
  markerLayer = L.layerGroup().addTo(map);

  if (hub) {
    hubMarker = L.circleMarker([hub.lat, hub.lng], {
      radius: markerRadius(map.getZoom()) + 2,
      color: "#ffffff",
      weight: 1,
      fillColor: "#111214",
      fillOpacity: 1,
    })
      .bindTooltip(`${hub.name} · hub`, { direction: "top", offset: [0, -8] })
      .addTo(hubLayer);
  }
  setCommunities(communities, onSelect);
  map.on("zoomend", resizeMarkers);

  await new Promise((resolve) => setTimeout(resolve, 80));
  map.invalidateSize();
  return {
    selectCommunity,
    setTheme,
    setBasemap,
    setCommunities: (next) => setCommunities(next, onSelect),
    setVisibleStatuses,
    showImagery,
  };
}

/**
 * Marker radius (px) at a zoom level: small when zoomed out, so neighbouring communities don't cover each other, and
 * full size from zoom 12 in. The hub is drawn 2 px larger.
 */
export function markerRadius(zoom) {
  return Math.max(2, Math.min(7, Math.round(zoom) - 5));
}

/** Resizes every marker to the current zoom. */
function resizeMarkers() {
  const radius = markerRadius(map.getZoom());
  for (const { marker } of markers.values()) marker.setRadius(radius);
  hubMarker?.setRadius(radius + 2);
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
  communities.forEach((community) => {
    const status = statusOf(community);
    const marker = L.circleMarker([community.lat, community.lng], {
      radius: markerRadius(map.getZoom()),
      color: "#ffffff",
      weight: 1,
      fillColor: STATUSES[status].color,
      fillOpacity: 1,
    }).bindTooltip(`${community.name} · ${STATUSES[status].label}`, { direction: "top", offset: [0, -7] });
    marker.on("click", () => onSelect(community.id));
    markers.set(community.id, { marker, status });
  });
  drawMarkers();
}

/** Shows only the communities in `statuses` (status codes). */
function setVisibleStatuses(statuses) {
  visibleStatuses = new Set(statuses);
  drawMarkers();
}

function drawMarkers() {
  markerLayer.clearLayers();
  for (const { marker, status } of markers.values()) {
    if (visibleStatuses.has(status)) marker.addTo(markerLayer);
  }
}

function selectCommunity(id) {
  const entry = markers.get(id);
  if (!entry) return;
  map.flyTo(entry.marker.getLatLng(), Math.max(map.getZoom(), COMMUNITY_ZOOM), { duration: 0.5 });
  if (visibleStatuses.has(entry.status)) entry.marker.openTooltip();
}

/**
 * Draws exactly `layers` ({ id, url, bounds, opacity }, bottom to top) over the basemap. An image already on the map
 * is kept and only its opacity changes, so dragging the opacity slider never reloads it.
 */
function showImagery(layers) {
  const wanted = new Set(layers.map((layer) => layer.url));
  for (const [url, image] of images) {
    if (!wanted.has(url)) {
      imageryLayer.removeLayer(image);
      images.delete(url);
    }
  }
  for (const layer of layers) {
    let image = images.get(layer.url);
    if (image) {
      image.setOpacity(layer.opacity);
    } else {
      image = L.imageOverlay(layer.url, layer.bounds, { opacity: layer.opacity, pane: IMAGERY_PANE, alt: layer.id });
      image.on("error", () => onImageError(layer.id));
      image.addTo(imageryLayer);
      images.set(layer.url, image);
    }
    image.bringToFront();
  }
}
