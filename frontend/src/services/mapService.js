import { STATUSES, statusOf } from "../status.js";

// Tefé, when the API has no hub to centre on
const DEFAULT_CENTER = [-3.354, -64.711];
const REGION_ZOOM = 9;
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

let map;
let tiles;
let usingFallback = false;
const markers = new Map();

/** The CARTO tile URL of the day ("light") or night ("dark") map, with the API key. */
export function tileUrl(theme) {
  const style = CARTO_STYLES[theme] ?? CARTO_STYLES.light;
  return `https://{s}.basemaps.cartocdn.com/${style}/{z}/{x}/{y}{r}.png?key=${CARTO_API_KEY}`;
}

/** Draws the hub and every community, coloured by status; returns the controls to focus one and to switch theme. */
export async function createMap(hub, communities, onSelect, theme = "light") {
  map = L.map("map", { zoomControl: true, scrollWheelZoom: false }).setView(
    hub ? [hub.lat, hub.lng] : DEFAULT_CENTER,
    REGION_ZOOM,
  );
  tiles = L.tileLayer(tileUrl(theme), { attribution: CARTO_ATTRIBUTION, subdomains: "abcd", maxZoom: 18 }).addTo(map);
  tiles.on("tileerror", useOpenStreetMap);
  const layer = L.layerGroup().addTo(map);

  if (hub) {
    L.circleMarker([hub.lat, hub.lng], { radius: 9, color: "#ffffff", weight: 3, fillColor: "#111214", fillOpacity: 1 })
      .bindTooltip(`${hub.name} · hub`, { direction: "top", offset: [0, -8] })
      .addTo(layer);
  }
  communities.forEach((community) => {
    const status = statusOf(community);
    const marker = L.circleMarker([community.lat, community.lng], {
      radius: 7,
      color: "#ffffff",
      weight: 2,
      fillColor: STATUSES[status].color,
      fillOpacity: 1,
    }).bindTooltip(`${community.name} · ${STATUSES[status].label}`, { direction: "top", offset: [0, -7] });
    marker.on("click", () => onSelect(community.id));
    marker.addTo(layer);
    markers.set(community.id, marker);
  });

  await new Promise((resolve) => setTimeout(resolve, 80));
  map.invalidateSize();
  return { selectCommunity, setTheme };
}

function selectCommunity(id) {
  const marker = markers.get(id);
  if (!marker) return;
  map.flyTo(marker.getLatLng(), Math.max(map.getZoom(), COMMUNITY_ZOOM), { duration: 0.5 });
  marker.openTooltip();
}

/** Switches the basemap to the day ("light") or night ("dark") style. */
function setTheme(theme) {
  // The OpenStreetMap fallback has a single style; the CSS of the theme darkens it
  if (!usingFallback) tiles.setUrl(tileUrl(theme));
}

/** CARTO refused a tile: swap to OpenStreetMap for good, instead of leaving the map without a background. */
function useOpenStreetMap() {
  if (usingFallback) return;
  usingFallback = true;
  console.warn("CARTO refused the basemap tiles (is this domain allowed for the key?): using OpenStreetMap instead.");
  map.removeLayer(tiles);
  tiles = L.tileLayer(OSM_URL, { attribution: OSM_ATTRIBUTION, maxZoom: 19 }).addTo(map);
  map.getContainer().classList.add("map-fallback");
}
