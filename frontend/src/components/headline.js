// The result of the day, written as a sentence: how many communities lost their water route, with the number in the
// stencil face, and a line on the ones close behind. Built from the communities of the pass on screen.
import { escapeHtml, daysBetween, formatDay, formatShortDay } from "../format.js";
import { countByStatus, isUnconfirmedLoss, statusOf } from "../status.js";

/** The telemetry line above the sentence: the pass day, how many communities it watched, and how it relates to `day`. */
export function headlineMeta(pass, watched, day = null) {
  const parts = [formatDay(pass.acquiredOn), `${watched} ${watched === 1 ? "community" : "communities"} watched`];
  if (day && day !== pass.acquiredOn) {
    const gap = daysBetween(pass.acquiredOn, day);
    if (gap > 0) parts.push(`latest pass before ${formatShortDay(day)}, ${gap} ${gap === 1 ? "day" : "days"} earlier`);
    else
      parts.push(`no full pass before ${formatShortDay(day)}: first one ${-gap} ${gap === -1 ? "day" : "days"} later`);
  }
  return parts.join(" · ");
}

/** The sentence: "9 communities have lost the water route to Tefé." with its count apart, to be set in stencil. */
export function headlineSentence(isolated, hubName) {
  if (isolated === 0) return { count: 0, text: `communities have lost their water route to ${hubName}.` };
  return {
    count: isolated,
    text: `${isolated === 1 ? "community has" : "communities have"} lost the water route to ${hubName}.`,
  };
}

/** The line under it: the communities at risk, and how many of them lost their route on this pass for the first time. */
export function headlineSub(communities, thresholdM) {
  const atRisk = communities.filter((community) => statusOf(community) === "at_risk");
  const below = thresholdM == null ? "narrow channels" : `channels narrower than ${thresholdM} m`;
  if (atRisk.length === 0) {
    return thresholdM == null
      ? "Every other watched community still has a wide water route."
      : `Every other watched community has a route at least ${thresholdM} m wide.`;
  }
  const lost = atRisk.filter(isUnconfirmedLoss).length;
  const firstTime = lost > 0 ? `, ${lost} of them with no route on this pass for the first time` : "";
  return `${atRisk.length} more can only reach it through ${below}${firstTime}.`;
}

/**
 * Renders the headline band for `pass` (null: nothing to show yet). `day` is the day the user asked for, which may
 * have no pass of its own.
 */
export function renderHeadline(container, { pass, communities, hubName, thresholdM, day = null }) {
  if (!pass) {
    container.innerHTML = `<p class="headline-meta telemetry">No record yet</p>
      <h2 class="headline-title">No NISAR pass over the area yet.</h2>
      <p class="headline-sub">The record has no pass to show yet: the next pass is added once NASA publishes it.</p>`;
    return;
  }
  const meta = headlineMeta(pass, communities.length, day);
  if (!pass.evaluated) {
    const share = pass.coverage == null ? "" : ` (${Math.round(pass.coverage * 100)} % covered)`;
    container.innerHTML = `<p class="headline-meta telemetry">${escapeHtml(meta)}</p>
      <h2 class="headline-title">This pass covers only part of the area.</h2>
      <p class="headline-sub">The radar image is shown, but communities are not evaluated${share}. Pick a full pass in the log below.</p>`;
    return;
  }
  const { isolated } = countByStatus(communities);
  const sentence = headlineSentence(isolated, hubName);
  const tone = sentence.count > 0 ? "headline-count-alert" : "headline-count-clear";
  container.innerHTML = `<p class="headline-meta telemetry">${escapeHtml(meta)}</p>
    <h2 class="headline-title"><span class="headline-count ${tone}">${sentence.count}</span> ${escapeHtml(sentence.text)}</h2>
    <p class="headline-sub">${escapeHtml(headlineSub(communities, thresholdM))}</p>`;
}
