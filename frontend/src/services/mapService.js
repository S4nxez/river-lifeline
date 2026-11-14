import { STATUSES, statusOf } from "../status.js";

// Tefé, when the API has no hub to centre on
const DEFAULT_CENTER = [-3.354, -64.711];
const REGION_ZOOM = 9;
const COMMUNITY_ZOOM = 11;
const TILE_URL = "https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png";

let map;
const markers = new Map();

/** The CARTO Dark Matter tile URL, with the basemaps API key when there is one (without it the tiles are watermarked). */
export function tileUrl(apiKey) {
  return apiKey ? `${TILE_URL}?key=${encodeURIComponent(apiKey)}` : TILE_URL;
}

/** The CARTO key from ./config.js, which the server writes from its environment (see Caddyfile); none if it's missing. */
function cartoApiKey() {
  return globalThis.RIVER_LIFELINE_CONFIG?.cartoApiKey || null;
}
/** Draws the hub and every community, coloured by status; returns the controls to focus one. */
export async function createMap(hub, communities, onSelect) {
  map = L.map("map", { zoomControl: true, scrollWheelZoom: false }).setView(
    hub ? [hub.lat, hub.lng] : DEFAULT_CENTER,
    REGION_ZOOM,
  );
  L.tileLayer(tileUrl(cartoApiKey()), {
    attribution: "&copy; OpenStreetMap &copy; CARTO",
    subdomains: "abcd",
    maxZoom: 18,
  }).addTo(map);
  const layer = L.layerGroup().addTo(map);

  if (hub) {
    L.circleMarker([hub.lat, hub.lng], { radius: 9, color: "#edf4f7", weight: 3, fillColor: "#101b25", fillOpacity: 1 })
      .bindTooltip(`${hub.name} · hub`, { direction: "top", offset: [0, -8] })
      .addTo(layer);
  }
  communities.forEach((community) => {
    const status = statusOf(community);
    const marker = L.circleMarker([community.lat, community.lng], {
      radius: 7,
      color: "#101b25",
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
  return { selectCommunity };
}

function selectCommunity(id) {
  const marker = markers.get(id);
  if (!marker) return;
  map.flyTo(marker.getLatLng(), Math.max(map.getZoom(), COMMUNITY_ZOOM), { duration: 0.5 });
  marker.openTooltip();
}
