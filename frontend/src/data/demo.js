// Shown when the API can't be reached: a synthetic drought, in the page's view model (what services/api.js returns).
import { countByStatus, latestKnown } from "../status.js";
import { addDays } from "../format.js";

const hub = { id: "tefe", name: "Tefé", kind: "hub", lat: -3.354, lng: -64.711 };

// The demo area, as the API describes one (services/api.js): its own threshold, no bounds (the map fits the places)
const area = {
  id: "tefe",
  name: "Tefé",
  region: "Amazonas",
  center: { lat: hub.lat, lng: hub.lng },
  radiusKm: null,
  bounds: null,
  connectedMinWidthM: 60,
  widthClassesM: [40, 60, 100, 200, 400],
  gauge: null,
};

const passes = [
  { id: "nisar-2026-08-04", acquiredOn: "2026-08-04", riverLevelCm: 1650, orbitDirection: "ascending", track: 161 },
  { id: "nisar-2026-08-16", acquiredOn: "2026-08-16", riverLevelCm: 1420, orbitDirection: "descending", track: 97 },
  { id: "nisar-2026-08-28", acquiredOn: "2026-08-28", riverLevelCm: 1180, orbitDirection: "ascending", track: 161 },
  { id: "nisar-2026-09-09", acquiredOn: "2026-09-09", riverLevelCm: 980, orbitDirection: "descending", track: 97 },
];

// Per community, one [status, route width class, Sentinel-1 agrees] per pass
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

const REASONS = {
  connected: "route_ge_60m",
  at_risk: "narrow_route",
  isolated: "no_route_confirmed",
  no_data: "outside_coverage",
};

function assessments(track) {
  return track.map(([status, routeWidthM, agrees], index) => {
    const pass = passes[index];
    return {
      passId: pass.id,
      acquiredOn: pass.acquiredOn,
      status,
      reason: REASONS[status],
      routeWidthM,
      sentinel1: agrees === null ? null : { sceneOn: addDays(pass.acquiredOn, -1), agrees },
    };
  });
}

/** Every community as pass `index` saw it, with what changed since the pass before. */
function communitiesOn(index) {
  return communities.map(({ place, track }) => {
    const latest = assessments(track)[index];
    const previousStatus = index > 0 ? track[index - 1][0] : null;
    return { ...place, latest, previousStatus, changed: previousStatus != null && previousStatus !== latest.status };
  });
}

/** The demo passes, oldest first, with their counts, as `loadOverview` lists them. */
function passList() {
  return passes.map((pass, index) => {
    const counts = countByStatus(communitiesOn(index));
    return {
      id: pass.id,
      acquiredOn: pass.acquiredOn,
      acquiredAt: `${pass.acquiredOn}T10:00:00Z`,
      platform: "NISAR",
      track: pass.track,
      orbitDirection: pass.orbitDirection,
      evaluated: true,
      coverage: null,
      riverLevelCm: pass.riverLevelCm,
      counts: {
        monitored: communities.length,
        connected: counts.connected,
        atRisk: counts.at_risk,
        isolated: counts.isolated,
        noData: counts.no_data,
        changed: communitiesOn(index).filter((community) => community.changed).length,
      },
      previousPassId: index > 0 ? passes[index - 1].id : null,
    };
  });
}

/** The demo overview, as `loadOverview` returns it: the demo data has no imagery. */
export function demoOverview() {
  const last = passes.length - 1;
  return {
    area,
    hub,
    passes: passList(),
    passId: passes[last].id,
    layerCatalog: [],
    // Each community as its latest assessment that saw it, like the API's latest pass
    communities: communities.map(({ place, track }, index) => ({
      ...communitiesOn(last)[index],
      ...place,
      latest: latestKnown(assessments(track)),
    })),
    imagery: [],
    plate: null,
    recordUpdatedAt: null,
  };
}

/** One demo pass, as `loadPass` returns it: every community as that pass saw it; null for an unknown pass. */
export function demoPass(passId) {
  const index = passes.findIndex((pass) => pass.id === passId);
  if (index < 0) return null;
  return { communities: communitiesOn(index), imagery: [], plate: null };
}

/** The demo history of a community, as `loadHistory` returns it, or null for an unknown id. */
export function demoHistory(id) {
  const community = communities.find(({ place }) => place.id === id);
  return community
    ? { ...community.place, monitored: true, exclusionReason: null, assessments: assessments(community.track) }
    : null;
}

/** A demo gauge: one reading a day, falling straight from one pass's level to the next. */
export function demoRiverLevel() {
  const readings = [];
  for (let index = 1; index < passes.length; index += 1) {
    const from = passes[index - 1];
    const to = passes[index];
    const days = 12;
    for (let day = 0; day < days; day += 1) {
      readings.push({
        date: addDays(from.acquiredOn, day),
        levelCm: Math.round(from.riverLevelCm + ((to.riverLevelCm - from.riverLevelCm) * day) / days),
      });
    }
  }
  readings.push({ date: passes.at(-1).acquiredOn, levelCm: passes.at(-1).riverLevelCm });
  return { stationName: null, provider: null, readings };
}
