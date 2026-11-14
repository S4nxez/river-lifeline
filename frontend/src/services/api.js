// The backend's monitor API. Relative URLs: the app is served under a path prefix in production.
import { demoHistory, demoOverview } from "../data/demo.js";

const JSON_HEADERS = { headers: { Accept: "application/json" } };

/**
 * The overview of every community. Falls back to the demo data when the API can't be reached or has no
 * communities yet; `live` tells which one the page shows.
 */
export async function loadOverview() {
  try {
    const response = await fetch("./api/communities", JSON_HEADERS);
    if (response.ok) {
      const overview = await response.json();
      if (Array.isArray(overview.communities) && overview.communities.length > 0) return { overview, live: true };
    }
  } catch (error) {
    console.info("Monitor API unavailable; showing the demo data.", error);
  }
  return { overview: demoOverview(), live: false };
}

/** Every assessment of a community, oldest first; null when it can't be loaded. */
export async function loadHistory(communityId, live) {
  if (!live) return demoHistory(communityId);
  try {
    const response = await fetch(`./api/communities/${encodeURIComponent(communityId)}`, JSON_HEADERS);
    return response.ok ? await response.json() : null;
  } catch (error) {
    console.info(`History of ${communityId} unavailable.`, error);
    return null;
  }
}
