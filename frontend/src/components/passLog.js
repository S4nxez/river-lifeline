// The pass log: every NISAR pass over the area as a strip that leans the way the satellite was flying, split by how
// many communities were connected, at risk or isolated, over the official river level. Drawn by hand in SVG: the
// geometry (`passLogScene`) is plain numbers, kept apart from the markup so it can be checked on its own.
import { addDays, daysBetween, escapeHtml, formatDay, formatMonthTick, formatShortDay } from "../format.js";

export const LOG_HEIGHT = 240;
const MARGIN = { left: 12, right: 64, top: 34, bottom: 24 };
const MAX_STRIP = 130;
const LEAN = 0.14; // horizontal shift per pixel of height: about 8°
const GAP_DAYS = 60;
const RIVER_BREAK_DAYS = 5;
const RIVER_MARGIN_CM = 60;

const DIRECTIONS = { ascending: 1, descending: -1 };

/** A strip's slice from height `y0` up to `y1`, leaning by its direction: the points of an SVG polygon. */
function slice(x, halfWidth, base, y0, y1, lean) {
  const shift = (y) => (base - y) * LEAN * lean;
  const point = (px, py) => `${(px + shift(py)).toFixed(1)},${py.toFixed(1)}`;
  return [point(x - halfWidth, y0), point(x + halfWidth, y0), point(x + halfWidth, y1), point(x - halfWidth, y1)].join(
    " ",
  );
}

/** What a strip says, for its tooltip and screen readers. */
export function stripLabel(pass) {
  const parts = [formatDay(pass.acquiredOn)];
  if (pass.track != null) parts.push(`track ${pass.track}${pass.orbitDirection ? ` ${pass.orbitDirection}` : ""}`);
  if (!pass.evaluated) parts.push("covers part of the area: image only");
  else if (pass.counts) {
    const { isolated = 0, atRisk = 0, connected = 0 } = pass.counts;
    parts.push(`isolated ${isolated}, at risk ${atRisk}, connected ${connected}`);
  }
  if (pass.riverLevelCm != null) parts.push(`river ${pass.riverLevelCm} cm`);
  return parts.join(" · ");
}

/** The river level scale: whole metres around the readings, with a margin. */
function riverScale(readings) {
  const levels = readings.map((reading) => reading.levelCm);
  const low = Math.floor((Math.min(...levels) - RIVER_MARGIN_CM) / 100) * 100;
  const high = Math.ceil((Math.max(...levels) + RIVER_MARGIN_CM) / 100) * 100;
  return { low, high };
}

/**
 * Everything the pass log draws, in pixels, for a log `width` px wide: the time axis (`ticks`, `gaps`, `today`), the
 * `strips` of the passes, the `ghosts` of the expected ones and the `river` line with its scale.
 */
export function passLogScene({ passes, river = null, today, expected = [], width }) {
  const base = LOG_HEIGHT - MARGIN.bottom;
  const days = passes.map((pass) => pass.acquiredOn);
  const first = days[0] ?? today;
  const latest = [days.at(-1) ?? today, today, ...expected.map((pass) => pass.day)].sort().at(-1);
  const start = addDays(first, -10);
  const end = addDays(latest, 6);
  const span = Math.max(1, daysBetween(start, end));
  const plotWidth = Math.max(1, width - MARGIN.left - MARGIN.right);
  const x = (day) => MARGIN.left + (daysBetween(start, day) / span) * plotWidth;

  // Strips are 62 % of the closest spacing between pass days, between 4 and 12 px
  const distinct = [...new Set(days)];
  let closest = Infinity;
  for (let index = 1; index < distinct.length; index += 1) {
    closest = Math.min(closest, x(distinct[index]) - x(distinct[index - 1]));
  }
  const stripWidth = Math.max(4, Math.min(12, closest * 0.62));
  const half = stripWidth / 2;

  const sameDay = new Map();
  const strips = passes.map((pass, index) => {
    const siblings = passes.filter((other) => other.acquiredOn === pass.acquiredOn);
    const order = sameDay.get(pass.acquiredOn) ?? 0;
    sameDay.set(pass.acquiredOn, order + 1);
    const cx = x(pass.acquiredOn) + (order - (siblings.length - 1) / 2) * (stripWidth + 2);
    const lean = DIRECTIONS[pass.orbitDirection] ?? 0;
    const counts = pass.counts ?? {};
    const isolated = counts.isolated ?? 0;
    const atRisk = counts.atRisk ?? 0;
    const connected = counts.connected ?? 0;
    const watched = counts.monitored || isolated + atRisk + connected + (counts.noData ?? 0) || 1;
    const scale = MAX_STRIP / watched;
    let top = base;
    const segments = [];
    if (pass.evaluated) {
      for (const [status, count] of [
        ["isolated", isolated],
        ["at_risk", atRisk],
        ["connected", connected],
      ]) {
        if (count <= 0) continue;
        const y1 = top - count * scale;
        segments.push({ status, points: slice(cx, half, base, top, y1, lean) });
        top = y1;
      }
    } else {
      top = base - MAX_STRIP * 0.45;
    }
    return {
      id: pass.id,
      index,
      evaluated: pass.evaluated,
      x: cx,
      top,
      segments,
      outline: slice(cx, half, base, base, top, lean),
      labelX: cx + (base - top) * LEAN * lean,
      label: stripLabel(pass),
      day: pass.acquiredOn,
    };
  });

  const ghosts = expected.map((pass) => {
    const cx = x(pass.day);
    const lean = DIRECTIONS[pass.orbitDirection] ?? 0;
    const top = base - MAX_STRIP * 0.4;
    const direction = pass.orbitDirection ? ` ${pass.orbitDirection}` : "";
    return {
      day: pass.day,
      outline: slice(cx, half, base, base, top, lean),
      label: `Expected pass ${formatDay(pass.day)}${pass.track != null ? ` · track ${pass.track}${direction}` : ""}`,
    };
  });

  const ticks = [];
  let month = `${start.slice(0, 7)}-01`;
  if (month < start) month = addDays(`${start.slice(0, 7)}-28`, 4).slice(0, 7) + "-01";
  while (month <= end) {
    ticks.push({ x: x(month), label: formatMonthTick(month) });
    month = addDays(`${month.slice(0, 7)}-28`, 4).slice(0, 7) + "-01";
  }

  const gaps = [];
  for (let index = 1; index < distinct.length; index += 1) {
    if (daysBetween(distinct[index - 1], distinct[index]) > GAP_DAYS) {
      gaps.push({ x: (x(distinct[index - 1]) + x(distinct[index])) / 2 });
    }
  }

  let riverLine = null;
  const readings = (river?.readings ?? []).filter((reading) => reading.date >= start && reading.date <= end);
  if (readings.length > 1) {
    const { low, high } = riverScale(readings);
    const y = (cm) => base - 6 - ((cm - low) / (high - low)) * (base - 6 - (MARGIN.top + 4));
    const runs = [];
    let run = [];
    readings.forEach((reading, index) => {
      if (index > 0 && daysBetween(readings[index - 1].date, reading.date) > RIVER_BREAK_DAYS) {
        runs.push(run);
        run = [];
      }
      run.push(`${x(reading.date).toFixed(1)},${y(reading.levelCm).toFixed(1)}`);
    });
    runs.push(run);
    const middle = Math.round((low + high) / 200) * 100;
    riverLine = {
      paths: runs.filter((points) => points.length > 1).map((points) => `M${points.join("L")}`),
      labels: [high, middle, low].map((cm) => ({ cm, y: y(cm) })),
    };
  }

  return {
    width,
    base,
    ticks,
    gaps,
    strips,
    ghosts,
    river: riverLine,
    today: today >= start && today <= end ? x(today) : null,
  };
}

/** The legend under the log, with swatches that lean like the strips. */
const LEGEND = `<ul class="log-legend telemetry">
  <li><i class="log-swatch log-swatch-isolated"></i>Isolated</li>
  <li><i class="log-swatch log-swatch-at_risk"></i>At risk</li>
  <li><i class="log-swatch log-swatch-connected"></i>Connected</li>
  <li><i class="log-swatch log-swatch-partial"></i>Pass covers part of the area: image only</li>
  <li><i class="log-swatch log-swatch-river"></i>River level (cm)</li>
</ul>`;

/**
 * Draws the log in `container`. `data`: `passes` (oldest first), `passId` (the selected one), `river` (the gauge's
 * readings), `today`, `expected` passes and `animate` (the "downlink" entrance, first draw only). `onSelect(passId)`
 * hears clicks and Enter or Space on a strip.
 */
export function renderPassLog(container, { passes, passId, river, today, expected, animate = false }, onSelect) {
  const width = Math.round(container.clientWidth || 960);
  const scene = passLogScene({ passes, river, today, expected, width });
  const { base } = scene;
  const height = LOG_HEIGHT;

  const ticks = scene.ticks
    .map(
      (tick) =>
        `<line class="log-tick" x1="${tick.x.toFixed(1)}" x2="${tick.x.toFixed(1)}" y1="${base}" y2="${base + 4}"/><text class="log-text" x="${(tick.x + 3).toFixed(1)}" y="${base + 16}">${tick.label}</text>`,
    )
    .join("");
  const gaps = scene.gaps
    .map(
      (gap) =>
        `<text class="log-text log-gap" x="${gap.x.toFixed(1)}" y="${base - 8}" text-anchor="middle">no NISAR data published</text>`,
    )
    .join("");
  const riverLine = scene.river
    ? `<g class="log-river">${scene.river.paths.map((path) => `<path d="${path}"/>`).join("")}</g>
       <g class="log-river-scale">${scene.river.labels
         .map(
           (label) =>
             `<text class="log-text" x="${width - 8}" y="${(label.y + 3).toFixed(1)}" text-anchor="end">${label.cm}</text>`,
         )
         .join("")}<text class="log-text" x="${width - 8}" y="${MARGIN.top - 14}" text-anchor="end">cm</text></g>`
    : "";
  const todayLine =
    scene.today == null
      ? ""
      : `<line class="log-today" x1="${scene.today.toFixed(1)}" x2="${scene.today.toFixed(1)}" y1="${MARGIN.top - 6}" y2="${base}"/><text class="log-text" x="${(scene.today + 4).toFixed(1)}" y="${MARGIN.top - 8}">today</text>`;
  const ghosts = scene.ghosts
    .map(
      (ghost) =>
        `<polygon class="log-ghost" points="${ghost.outline}"><title>${escapeHtml(ghost.label)}</title></polygon>`,
    )
    .join("");
  const strips = scene.strips
    .map((strip) => {
      const selected = strip.id === passId;
      const label = escapeHtml(strip.label);
      const bars = strip.segments
        .map((segment) => `<polygon class="log-bar log-bar-${segment.status}" points="${segment.points}"/>`)
        .join("");
      const dateLabel = selected
        ? `<text class="log-text log-selected-day" x="${strip.labelX.toFixed(1)}" y="${(strip.top - 8).toFixed(1)}" text-anchor="middle">${formatShortDay(strip.day).toUpperCase()}</text>`
        : "";
      return `<g class="log-strip${selected ? " is-selected" : ""}${strip.evaluated ? "" : " is-partial"}" role="button" tabindex="0" aria-pressed="${selected}" aria-label="${label}" data-pass="${escapeHtml(strip.id)}" style="--i:${strip.index}">
        <title>${label}</title><g class="log-bars">${bars}</g><polygon class="log-outline" points="${strip.outline}"/>${dateLabel}</g>`;
    })
    .join("");

  container.innerHTML = `<svg class="log-svg${animate ? " log-downlink" : ""}" viewBox="0 0 ${width} ${height}" width="100%" height="${height}" role="group" aria-label="Pass log: one strip per NISAR pass">
      ${riverLine}<line class="log-base" x1="${MARGIN.left}" x2="${width - MARGIN.right + 8}" y1="${base}" y2="${base}"/>${ticks}${gaps}${todayLine}${ghosts}${strips}
    </svg>${LEGEND}`;

  container.querySelectorAll(".log-strip").forEach((strip) => {
    strip.addEventListener("click", () => onSelect(strip.dataset.pass));
    strip.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        onSelect(strip.dataset.pass);
      }
    });
  });
}
