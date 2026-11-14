// Shown when the API can't be reached: a synthetic drought, in the page's view model (what services/api.js returns).
import { countByStatus, latestKnown } from "../status.js";

const hub = { id: "tefe", name: "Tefé", kind: "hub", lat: -3.354, lng: -64.711 };

// The demo area, as the API describes one (services/api.js): its own threshold, no bounds (the map fits the places)
const area = {
  id: "tefe",
  name: "Tefé",
  region: "Amazonas",
  center: { lat: hub.lat, lng: hub.lng },
  bounds: null,
  connectedMinWidthM: 60,
};

const passes = [
  { id: "nisar-2026-08-04", acquiredOn: "2026-08-04", riverLevelCm: 1650, sentinel1On: null },
  { id: "nisar-2026-08-16", acquiredOn: "2026-08-16", riverLevelCm: 1420, sentinel1On: "2026-08-15" },
  { id: "nisar-2026-08-28", acquiredOn: "2026-08-28", riverLevelCm: 1180, sentinel1On: "2026-08-27" },
  { id: "nisar-2026-09-09", acquiredOn: "2026-09-09", riverLevelCm: 980, sentinel1On: "2026-09-08" },
];

// Per community, one [status, route width, Sentinel-1 agrees] per pass
const communities = [
  {
    place: { id: "alvaraes", name: "Alvarães", kind: "town", lat: -3.221, lng: -64.804 },
    track: [
      ["connected", 400, null],
      ["connected", 400, true],
      ["connected", 200, true],
      ["connected", 100, true],
    ],
  },
  {
    place: { id: "uarini", name: "Uarini", kind: "town", lat: -2.99, lng: -65.113 },
    track: [
      ["connected", 200, null],
      ["connected", 200, true],
      ["at_risk", 40, false],
      ["isolated", null, true],
    ],
  },
  {
    place: { id: "nogueira", name: "Nogueira", kind: "village", lat: -3.295, lng: -64.86 },
    track: [
      ["connected", 100, null],
      ["at_risk", 40, true],
      ["isolated", null, true],
      ["isolated", null, true],
    ],
  },
  {
    place: { id: "caiambe", name: "Caiambé", kind: "village", lat: -3.545, lng: -64.4 },
    track: [
      ["connected", 200, null],
      ["connected", 100, true],
      ["connected", 60, true],
      ["no_data", null, null],
    ],
  },
];

function assessments(track) {
  return track.map(([status, routeWidthM, agrees], index) => {
    const pass = passes[index];
    return {
      passId: pass.id,
      acquiredOn: pass.acquiredOn,
      status,
      routeWidthM,
      sentinel1: agrees === null ? null : { sceneOn: pass.sentinel1On, agrees },
    };
  });
}

/** The demo passes, newest first, as the pass selector lists them. */
function passList() {
  return [...passes].reverse().map((pass) => ({
    id: pass.id,
    acquiredOn: pass.acquiredOn,
    platform: "NISAR",
    track: null,
    evaluated: true,
    coverage: null,
    riverLevelCm: pass.riverLevelCm,
  }));
}

/** The demo overview, as `loadOverview` returns it: the demo data has no imagery. */
export function demoOverview() {
  const statuses = communities.map(({ place, track }) => ({ ...place, latest: latestKnown(assessments(track)) }));
  const last = passes.at(-1);
  const lastStatuses = communities.map(({ track }) => ({ latest: { status: track.at(-1)[0] } }));
  const counts = countByStatus(lastStatuses);
  return {
    area,
    hub,
    latestPass: {
      id: last.id,
      mission: "NISAR",
      acquiredOn: last.acquiredOn,
      riverLevelCm: last.riverLevelCm,
      counts: {
        connected: counts.connected,
        atRisk: counts.at_risk,
        isolated: counts.isolated,
        noData: counts.no_data,
      },
    },
    passId: last.id,
    passes: passList(),
    layerCatalog: [],
    communities: statuses,
    imagery: [],
  };
}

/** One demo pass, as `loadPass` returns it: every community as that pass saw it; null for an unknown pass. */
export function demoPass(passId) {
  const index = passes.findIndex((pass) => pass.id === passId);
  if (index < 0) return null;
  return {
    communities: communities.map(({ place, track }) => ({ ...place, latest: assessments(track)[index] })),
    imagery: [],
  };
}

/** The demo history of a community, as `loadHistory` returns it, or null for an unknown id. */
export function demoHistory(id) {
  const community = communities.find(({ place }) => place.id === id);
  return community ? { ...community.place, assessments: assessments(community.track) } : null;
}
