// The mission clock: which pass a day shows, the evaluated passes either side of one, when NISAR is due back over the
// area, and the "Situation on ‹ day › · Play record" controls. Passes are in the view model's order, oldest first.
import { addDays, daysBetween, escapeHtml } from "../format.js";

// NISAR repeats its ground track every 12 days: a track that covered the area comes back after multiples of it
export const NISAR_REPEAT_DAYS = 12;

const evaluatedOf = (passes) => passes.filter((pass) => pass.evaluated);

/** The pass a day shows: the latest evaluated one on or before it, or the first evaluated one for an earlier day. */
export function passForDay(passes, day) {
  const evaluated = evaluatedOf(passes);
  const onOrBefore = evaluated.filter((pass) => pass.acquiredOn <= day);
  return onOrBefore.at(-1) ?? evaluated[0] ?? null;
}

/** The evaluated pass `step` places from `passId` (-1 the one before, +1 the one after), or null past either end. */
export function neighbourPass(passes, passId, step) {
  const evaluated = evaluatedOf(passes);
  const index = evaluated.findIndex((pass) => pass.id === passId);
  if (index === -1) return null;
  return evaluated[index + step] ?? null;
}

/**
 * When NISAR is next due over the area, one entry per track that has covered it in full: its last pass plus whole
 * repeat cycles, from `today` on. Soonest first.
 */
export function expectedPasses(passes, today, repeatDays = NISAR_REPEAT_DAYS) {
  const latestByTrack = new Map();
  for (const pass of evaluatedOf(passes)) {
    if (pass.track == null) continue;
    const key = `${pass.track}${pass.orbitDirection ?? ""}`;
    const known = latestByTrack.get(key);
    if (!known || pass.acquiredOn > known.acquiredOn) latestByTrack.set(key, pass);
  }
  return [...latestByTrack.values()]
    .map((pass) => {
      const cycles = Math.max(1, Math.ceil(daysBetween(pass.acquiredOn, today) / repeatDays));
      return {
        day: addDays(pass.acquiredOn, cycles * repeatDays),
        track: pass.track,
        orbitDirection: pass.orbitDirection,
      };
    })
    .sort((a, b) => a.day.localeCompare(b.day));
}

/**
 * The clock controls. `state`: `passes`, `passId`, `day` (the date field), `today` and `playing`. Handlers:
 * `onDay(day)`, `onStep(-1 | 1)` and `onPlay()`.
 */
export function renderClock(container, { passes, passId, day, today, playing }, handlers) {
  const evaluated = evaluatedOf(passes);
  const first = evaluated[0]?.acquiredOn ?? today;
  const hasBefore = neighbourPass(passes, passId, -1) !== null;
  const hasAfter = neighbourPass(passes, passId, 1) !== null;
  container.innerHTML = `
    <span class="clock-label telemetry" id="clock-label">Situation on</span>
    <div class="clock-day" role="group" aria-labelledby="clock-label">
      <button type="button" class="clock-step" data-step="-1" aria-label="Previous pass"${hasBefore ? "" : " disabled"}>‹</button>
      <input type="date" class="clock-date" aria-label="Day to show" value="${escapeHtml(day)}" min="${escapeHtml(first)}" max="${escapeHtml(today)}" />
      <button type="button" class="clock-step" data-step="1" aria-label="Next pass"${hasAfter ? "" : " disabled"}>›</button>
    </div>
    <button type="button" class="clock-play" aria-pressed="${playing}"${evaluated.length > 1 ? "" : " disabled"}>${playing ? "Pause" : "Play record"}</button>`;

  container
    .querySelectorAll("[data-step]")
    .forEach((button) => button.addEventListener("click", () => handlers.onStep(Number(button.dataset.step))));
  const field = container.querySelector(".clock-date");
  field.addEventListener("change", () => {
    if (field.value) handlers.onDay(field.value);
  });
  container.querySelector(".clock-play").addEventListener("click", () => handlers.onPlay());
}
