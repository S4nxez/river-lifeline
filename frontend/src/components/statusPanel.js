import { formatDay, formatWidth } from "../format.js";
import { statusOf } from "../status.js";

/**
 * What each status means for the people of a community. `thresholdM` is the area's connected width and `hubName` the
 * town the route leads to, both from the API.
 */
function interpretations(thresholdM, hubName) {
  const wide = thresholdM == null ? "wide enough for boats" : `at least ${thresholdM} m wide`;
  const narrow = thresholdM == null ? "narrower than the connected width" : `narrower than ${thresholdM} m`;
  return {
    connected: {
      tag: "CONNECTED",
      title: "Water route open",
      copy: `A channel ${wide} still links the community to ${hubName}.`,
    },
    at_risk: {
      tag: "AT RISK",
      title: "Route narrowing",
      copy: `A route remains but it is ${narrow}: larger boats may no longer get through. Plan supplies before it closes.`,
    },
    isolated: {
      tag: "ISOLATED",
      title: `No water route to ${hubName}`,
      copy: "The radar finds no water route left. The community likely needs supplies and health care by air or overland.",
    },
    no_data: {
      tag: "NO DATA",
      title: "Not observed",
      copy: "No radar pass has covered this community or its route on this date.",
    },
  };
}

/** What Sentinel-1, used only to cross-check NISAR, says about the assessment. */
function crossCheckNote(latest) {
  if (!latest?.sentinel1) return "No Sentinel-1 scene close enough in time to cross-check this pass.";
  const { sceneOn, agrees } = latest.sentinel1;
  return agrees
    ? `Sentinel-1 (${formatDay(sceneOn)}) confirms this result.`
    : `Sentinel-1 (${formatDay(sceneOn)}) disagrees with NISAR: verify on the ground before acting.`;
}

export function renderStatusPanel(container, community, { thresholdM = null, hubName = "the hub" } = {}) {
  const status = statusOf(community);
  const item = interpretations(thresholdM, hubName)[status];
  const latest = community.latest;
  container.innerHTML = `<div class="insight-status"><span class="level-indicator status-bg-${status}"></span><span>${item.tag}</span></div>
    <h3>${item.title}</h3><p class="insight-copy">${item.copy}</p>
    <div class="insight-metrics"><div><span>NARROWEST POINT</span><strong class="status-${status}">${formatWidth(latest?.routeWidthM)}</strong></div><div><span>NISAR PASS</span><strong>${latest ? formatDay(latest.acquiredOn) : "—"}</strong></div></div>
    <p class="crosscheck">${crossCheckNote(latest)}</p>`;
}
