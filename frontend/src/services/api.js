// The backend's monitor API (CONTRACTS.md, contract 2). Relative URLs: the app is served under a path prefix.
// The page still works on its own view model (hub, latest pass, communities with their latest assessment), so the
// responses are mapped into it here.
import { demoHistory, demoOverview } from "../data/demo.js";

const API = "./api/v1";
const DEFAULT_AOI = "tefe";
const JSON_HEADERS = { headers: { Accept: "application/json" } };

/** The area in the page URL (`?aoi=...`, CONTRACTS.md 4.2), or Tefé. */
export function aoiFromUrl(search = globalThis.location?.search ?? "") {
  const aoi = new URLSearchParams(search).get("aoi");
  return aoi && /^[a-z0-9-]+$/.test(aoi) ? aoi : DEFAULT_AOI;
}

async function getJson(url) {
  const response = await fetch(url, JSON_HEADERS);
  if (!response.ok) throw new Error(`${url} answered ${response.status}`);
  return response.json();
}

const dayOf = (timestamp) => timestamp?.slice(0, 10) ?? null;

/** `[lon, lat]` (GeoJSON order) to the page's `{ lat, lng }`. */
const latLng = ([lng, lat]) => ({ lat, lng });

function assessmentOf({ passId, acquiredAt, status, minWidthClassM }) {
  return { passId, acquiredOn: dayOf(acquiredAt), status, routeWidthM: minWidthClassM ?? null, sentinel1: null };
}

function overviewOf(aoi, summary, collection) {
  const hub = { id: aoi.id, name: aoi.name, kind: "hub", ...latLng(aoi.destination.location) };
  const counts = summary.counts ?? {};
  const latestPass = summary.pass_id
    ? {
        id: summary.pass_id,
        mission: "NISAR",
        acquiredOn: dayOf(summary.acquired_at),
        riverLevelCm: summary.river_level_m == null ? null : Math.round(summary.river_level_m * 100),
        counts: {
          connected: counts.connected,
          atRisk: counts.at_risk,
          isolated: counts.isolated,
          noData: counts.no_data,
        },
      }
    : null;
  // The feature geometry is the boarding point, where the route is measured from
  const communities = collection.features.map(({ geometry, properties }) => ({
    id: properties.id,
    name: properties.name,
    kind: properties.kind,
    ...latLng(geometry.coordinates),
    latest: assessmentOf({
      passId: collection.pass_id,
      acquiredAt: collection.acquired_at,
      status: properties.status,
      minWidthClassM: properties.min_width_class_m,
    }),
  }));
  return { hub, latestPass, communities };
}

/**
 * The overview of every community on the latest evaluated pass. Falls back to the demo data when the API can't be
 * reached or has no communities yet; `live` tells which one the page shows.
 */
export async function loadOverview(aoiId = aoiFromUrl()) {
  const base = `${API}/aois/${encodeURIComponent(aoiId)}`;
  try {
    const [aoi, summary, collection] = await Promise.all([
      getJson(base),
      getJson(`${base}/summary`),
      getJson(`${base}/passes/latest/communities`),
    ]);
    if (Array.isArray(collection.features) && collection.features.length > 0) {
      return { overview: overviewOf(aoi, summary, collection), live: true };
    }
  } catch (error) {
    console.info("Monitor API unavailable; showing the demo data.", error);
  }
  return { overview: demoOverview(), live: false };
}

function historyEntryOf(entry) {
  return assessmentOf({
    passId: entry.pass_id,
    acquiredAt: entry.acquired_at,
    status: entry.status,
    minWidthClassM: entry.min_width_class_m,
  });
}

/** Every evaluated assessment of a community, oldest first; null when it can't be loaded. */
export async function loadHistory(communityId, live, aoiId = aoiFromUrl()) {
  if (!live) return demoHistory(communityId);
  try {
    const url = `${API}/aois/${encodeURIComponent(aoiId)}/communities/${encodeURIComponent(communityId)}`;
    const card = await getJson(url);
    return {
      id: card.id,
      name: card.name,
      kind: card.kind,
      ...latLng(card.village_location),
      assessments: (card.history ?? []).map(historyEntryOf),
    };
  } catch (error) {
    console.info(`History of ${communityId} unavailable.`, error);
    return null;
  }
}
