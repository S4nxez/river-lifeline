import { STATUSES, statusOf } from "../status.js";

// Tefé, when the API has no hub to centre on
const DEFAULT_CENTER = [-3.354, -64.711];
const REGION_ZOOM = 9;
const COMMUNITY_ZOOM = 11;
// CARTO Dark Matter tiles. Keyless tiles are watermarked "API key required". The key is public by design (every
// tile URL carries it): what protects it is the CARTO dashboard, which restricts it to our domains, to basemaps
// and to a quota.
const CARTO_API_KEY = "cb1_58s2_1_e8ce9388cc9827c921fc7005";
const TILE_URL = `https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png?key=${CARTO_API_KEY}`;

let map;
const markers = new Map();

/** Draws the hub and every community, coloured by status; returns the controls to focus one. */
export async function createMap(hub, communities, onSelect) {
  map = L.map("map", { zoomControl: true, scrollWheelZoom: false }).setView(
    hub ? [hub.lat, hub.lng] : DEFAULT_CENTER,
    REGION_ZOOM,
  );
  L.tileLayer(TILE_URL, {
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
