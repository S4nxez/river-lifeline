// The backend's monitor API (CONTRACTS.md, contract 2). Relative URLs: the app is served under a path prefix.
// The page works on its own view model (the area, its passes, the communities on one pass and that pass's imagery),
// so the responses are mapped into it here. Nothing about the area is known in advance: it all comes from the API,
// and what the API doesn't give is left out of the view model (the page then hides what would use it).
import { demoHistory, demoOverview, demoPass, demoRiverLevel } from "../data/demo.js";

const API = "./api/v1";
const DEFAULT_AOI = "tefe";
const JSON_HEADERS = { headers: { Accept: "application/json" } };

/** The area in the page URL (`?aoi=...`, CONTRACTS.md 4.2), or Tefé. */
export function aoiFromUrl(search = globalThis.location?.search ?? "") {
  const aoi = new URLSearchParams(search).get("aoi");
  return aoi && /^[a-z0-9-]+$/.test(aoi) ? aoi : DEFAULT_AOI;
}

const areaBase = (aoiId) => `${API}/aois/${encodeURIComponent(aoiId)}`;

/** Where "Download this day" gets one pass's communities, as `csv` or `geojson` (the backend writes the file). */
export function exportUrl(passId, format, aoiId = aoiFromUrl()) {
  return `${areaBase(aoiId)}/passes/${encodeURIComponent(passId)}/export?format=${format}`;
}

async function getJson(url) {
  const response = await fetch(url, JSON_HEADERS);
  if (!response.ok) throw new Error(`${url} answered ${response.status}`);
  return response.json();
}

/** A response the page can do without (layer catalog, pass list, imagery): null when it fails. */
async function optionalJson(url) {
  try {
    return await getJson(url);
  } catch (error) {
    console.info(`${url} unavailable.`, error);
    return null;
  }
}

const dayOf = (timestamp) => timestamp?.slice(0, 10) ?? null;
const centimetres = (metres) => (metres == null ? null : Math.round(metres * 100));

/** `[lon, lat]` (GeoJSON order) to the page's `{ lat, lng }`. */
const latLng = ([lng, lat]) => ({ lat, lng });

/** A contract bbox `[west, south, east, north]` to Leaflet's `[[south, west], [north, east]]`. */
export function boundsOf(bbox) {
  if (!Array.isArray(bbox) || bbox.length !== 4) return null;
  const [west, south, east, north] = bbox;
  return [
    [south, west],
    [north, east],
  ];
}

/**
 * Where a layer image is. The API gives it from the server root (`/api/v1/...`), but the page lives under a path
 * prefix, so it is read relative to the page like every other API call. Full URLs are kept as they are.
 */
export function layerUrl(url) {
  if (/^https?:\/\//.test(url)) return url;
  if (url.startsWith("/api/")) return `.${url}`;
  return url;
}

function assessmentOf({ passId, acquiredAt, status, reason, minWidthClassM }) {
  return {
    passId,
    acquiredOn: dayOf(acquiredAt),
    status,
    reason: reason ?? null,
    routeWidthM: minWidthClassM ?? null,
    sentinel1: null,
  };
}

function areaOf(aoi) {
  return {
    id: aoi.id,
    name: aoi.name,
    region: aoi.region ?? null,
    center: aoi.center ? latLng(aoi.center) : null,
    radiusKm: aoi.radius_km ?? null,
    bounds: boundsOf(aoi.bbox),
    connectedMinWidthM: aoi.parameters?.connected_min_width_m ?? null,
    widthClassesM: aoi.parameters?.tested_widths_m ?? [],
    gauge: aoi.river_gauge
      ? { name: aoi.river_gauge.station_name ?? null, provider: aoi.river_gauge.provider ?? null }
      : null,
  };
}

function countsOf(counts) {
  if (!counts) return null;
  return {
    monitored: counts.monitored ?? null,
    connected: counts.connected ?? null,
    atRisk: counts.at_risk ?? null,
    isolated: counts.isolated ?? null,
    noData: counts.no_data ?? null,
    changed: counts.changed ?? null,
  };
}

function passOf(item) {
  return {
    id: item.id,
    acquiredOn: dayOf(item.acquired_at),
    acquiredAt: item.acquired_at ?? null,
    platform: item.platform ?? "NISAR",
    track: item.track ?? null,
    orbitDirection: item.orbit_direction ?? null,
    evaluated: item.evaluated !== false,
    coverage: item.coverage_fraction ?? null,
    riverLevelCm: centimetres(item.river_level_m),
    counts: countsOf(item.counts),
    previousPassId: item.previous_pass_id ?? null,
  };
}

/** The communities of a pass's GeoJSON: the feature geometry is the boarding point, where the route starts. */
function communitiesOf(collection) {
  return collection.features.map(({ geometry, properties }) => ({
    id: properties.id,
    name: properties.name,
    kind: properties.kind,
    ...latLng(geometry.coordinates),
    changed: properties.changed === true,
    previousStatus: properties.previous_status ?? null,
    boardingSource: properties.boarding_point ? (properties.boarding_point.source ?? "boarding point") : null,
    latest: assessmentOf({
      passId: collection.pass_id,
      acquiredAt: collection.acquired_at,
      status: properties.status,
      reason: properties.reason,
      minWidthClassM: properties.min_width_class_m,
    }),
  }));
}

/** The layer catalog of `/meta`: what each layer id is called and how it shows by default. */
function catalogOf(meta) {
  return (meta?.layers ?? []).map((layer) => ({
    id: layer.id,
    label: layer.label ?? layer.id,
    defaultVisible: layer.default_visible !== false,
    defaultOpacity: layer.default_opacity ?? 1,
  }));
}

/** The images of a pass, named and defaulted from the catalog; a layer missing from it still shows, by its id. */
export function imageryOf(passDetail, catalog) {
  return (passDetail?.layers ?? [])
    .filter((layer) => layer.url && boundsOf(layer.bounds))
    .map((layer) => {
      const known = catalog.find((entry) => entry.id === layer.id);
      return {
        id: layer.id,
        label: known?.label ?? layer.id,
        url: layerUrl(layer.url),
        bounds: boundsOf(layer.bounds),
        widthPx: layer.width_px ?? null,
        defaultVisible: known?.defaultVisible ?? false,
        defaultOpacity: known?.defaultOpacity ?? 1,
      };
    });
}

/**
 * The caption of a pass's radar image, as missions publish theirs: from its `image_card`, falling back on the pass
 * itself. Null without a pass.
 */
export function plateOf(detail) {
  if (!detail) return null;
  const card = detail.image_card ?? {};
  return {
    platform: detail.platform ?? "NISAR",
    band: card.band ?? detail.band ?? null,
    polarizations: card.polarizations ?? detail.polarizations ?? [],
    track: card.track ?? detail.track ?? null,
    orbitDirection: card.orbit_direction ?? detail.orbit_direction ?? null,
    acquiredAt: card.acquired_at ?? detail.acquired_at ?? null,
    pixelSizeM: card.pixel_size_m ?? detail.pixel_size_m ?? null,
    coverage: detail.coverage_fraction ?? null,
    product: card.product ?? null,
  };
}

function overviewOf(aoi, summary, collection, meta, passList, latestDetail) {
  const area = areaOf(aoi);
  const hub = { id: aoi.id, name: aoi.destination?.name ?? aoi.name, kind: "hub", ...latLng(aoi.destination.location) };
  const layerCatalog = catalogOf(meta);
  return {
    area,
    hub,
    // Oldest first, as the API lists them
    passes: (passList?.items ?? []).map(passOf),
    passId: collection.pass_id ?? summary.pass_id ?? null,
    layerCatalog,
    communities: communitiesOf(collection),
    imagery: imageryOf(latestDetail, layerCatalog),
    plate: plateOf(latestDetail),
    recordUpdatedAt: summary.catalog_generated_at ?? null,
  };
}

/**
 * The area and its latest evaluated pass: communities, passes, layer catalog and imagery. Falls back to the demo
 * data when the API can't be reached or has no communities yet; `live` tells which one the page shows.
 */
export async function loadOverview(aoiId = aoiFromUrl()) {
  const base = areaBase(aoiId);
  try {
    const [aoi, summary, collection, meta, passList, latestDetail] = await Promise.all([
      getJson(base),
      getJson(`${base}/summary`),
      getJson(`${base}/passes/latest/communities`),
      optionalJson(`${API}/meta`),
      optionalJson(`${base}/passes`),
      optionalJson(`${base}/passes/latest`),
    ]);
    if (Array.isArray(collection.features) && collection.features.length > 0) {
      return { overview: overviewOf(aoi, summary, collection, meta, passList, latestDetail), live: true };
    }
  } catch (error) {
    console.info("Monitor API unavailable; showing the demo data.", error);
  }
  return { overview: demoOverview(), live: false };
}

/**
 * One pass: its communities, its imagery (named from `layerCatalog`) and the caption of its radar image. Null when its
 * communities can't be loaded; a pass whose imagery fails still loads, without images.
 */
export async function loadPass(passId, live, layerCatalog = [], aoiId = aoiFromUrl()) {
  if (!live) return demoPass(passId);
  const base = `${areaBase(aoiId)}/passes/${encodeURIComponent(passId)}`;
  try {
    const [collection, detail] = await Promise.all([getJson(`${base}/communities`), optionalJson(base)]);
    return {
      communities: communitiesOf(collection),
      imagery: imageryOf(detail, layerCatalog),
      plate: plateOf(detail),
    };
  } catch (error) {
    console.info(`Pass ${passId} unavailable.`, error);
    return null;
  }
}

/** The area's river gauge, every daily reading in centimetres, oldest first; null when it can't be loaded. */
export async function loadRiverLevel(live, aoiId = aoiFromUrl()) {
  if (!live) return demoRiverLevel();
  const body = await optionalJson(`${areaBase(aoiId)}/river-level`);
  if (!body) return null;
  return {
    stationName: body.station_name ?? null,
    provider: body.provider ?? null,
    readings: (body.items ?? [])
      .filter((item) => item.date && item.level_m != null)
      .map((item) => ({ date: item.date, levelCm: centimetres(item.level_m) })),
  };
}

function historyEntryOf(entry) {
  return assessmentOf({
    passId: entry.pass_id,
    acquiredAt: entry.acquired_at,
    status: entry.status,
    reason: entry.reason,
    minWidthClassM: entry.min_width_class_m,
  });
}

/**
 * A community's card: whether it is monitored (and why not) and every evaluated assessment, oldest first. Null when it
 * can't be loaded.
 */
export async function loadHistory(communityId, live, aoiId = aoiFromUrl()) {
  if (!live) return demoHistory(communityId);
  try {
    const card = await getJson(`${areaBase(aoiId)}/communities/${encodeURIComponent(communityId)}`);
    return {
      id: card.id,
      name: card.name,
      kind: card.kind,
      monitored: card.monitored !== false,
      exclusionReason: card.exclusion_reason ?? null,
      ...(card.village_location ? latLng(card.village_location) : {}),
      assessments: (card.history ?? []).map(historyEntryOf),
    };
  } catch (error) {
    console.info(`History of ${communityId} unavailable.`, error);
    return null;
  }
}

/**
 * The experimental early warning of the area (`early_warning.json`, CONTRACTS.md), or null when the API has none (404)
 * or fails: the page then simply has no early warning section.
 */
export async function loadEarlyWarning(aoiId = aoiFromUrl()) {
  try {
    const response = await fetch(`${areaBase(aoiId)}/early-warning`, JSON_HEADERS);
    if (!response.ok) return null;
    const report = await response.json();
    return report?.experimental === true && Array.isArray(report.communities) ? report : null;
  } catch {
    return null;
  }
}
