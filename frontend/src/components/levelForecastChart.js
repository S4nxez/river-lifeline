// The river level behind the early warning: what the gauge read, the median forecast and its 10-90 % range, against
// the levels below which the selected community is at risk or cut off. Drawn by hand in SVG like the pass log, in the
// gauge's colour: never the status colours, a probability is not an observed status. The geometry
// (`forecastScene`) is plain numbers, kept apart from the markup so it can be checked on its own.
import { daysBetween, escapeHtml, formatMonthTick } from "../format.js";

export const CHART_HEIGHT = 230;
const MARGIN = { left: 12, right: 56, top: 18, bottom: 24 };
const LEVEL_MARGIN_CM = 50;

const pathOf = (points) =>
  points.map(([x, y], index) => `${index ? "L" : "M"}${x.toFixed(1)},${y.toFixed(1)}`).join("");

/**
 * Everything the chart draws, in pixels, for a chart `width` px wide: the `observed` and `median` lines, the `band`
 * between p10 and p90, the selected community's `thresholds`, the `issued` day, month `ticks` and the level `scale`.
 */
export function forecastScene(report, community, width) {
  const observed = (report?.observed_level ?? []).filter((row) => row.level_cm != null);
  const forecast = report?.level_forecast ?? [];
  const thresholds = [
    { kind: "at-risk", label: "At risk below", cm: community?.at_risk_below_cm ?? null },
    { kind: "cut-off", label: "Cut off below", cm: community?.isolated_below_cm ?? null },
  ].filter((threshold) => threshold.cm != null);
  const days = [...new Set([...observed.map((row) => row.date), ...forecast.map((row) => row.date)])].sort();
  if (!days.length) return null;
  const levels = [
    ...observed.map((row) => row.level_cm),
    ...forecast.flatMap((row) => [row.p10_cm, row.p90_cm]),
    ...thresholds.map((threshold) => threshold.cm),
  ];
  const low = Math.floor((Math.min(...levels) - LEVEL_MARGIN_CM) / 100) * 100;
  const high = Math.ceil((Math.max(...levels) + LEVEL_MARGIN_CM) / 100) * 100;
  const start = days[0];
  const span = Math.max(1, daysBetween(start, days.at(-1)));
  const plotWidth = Math.max(1, width - MARGIN.left - MARGIN.right);
  const base = CHART_HEIGHT - MARGIN.bottom;
  const x = (day) => MARGIN.left + (daysBetween(start, day) / span) * plotWidth;
  const y = (cm) => base - ((cm - low) / (high - low || 1)) * (base - MARGIN.top);

  const step = (high - low) / 100 > 8 ? 200 : 100;
  const scale = [];
  for (let cm = Math.ceil(low / step) * step; cm <= high; cm += step) scale.push({ cm, y: y(cm) });
  const ticks = days.filter((day) => day.endsWith("-01")).map((day) => ({ x: x(day), label: formatMonthTick(day) }));

  return {
    width,
    base,
    observed: observed.length ? pathOf(observed.map((row) => [x(row.date), y(row.level_cm)])) : null,
    median: forecast.length ? pathOf(forecast.map((row) => [x(row.date), y(row.p50_cm)])) : null,
    band: forecast.length
      ? [
          ...forecast.map((row) => [x(row.date), y(row.p90_cm)]),
          ...forecast
            .slice()
            .reverse()
            .map((row) => [x(row.date), y(row.p10_cm)]),
        ]
          .map(([px, py]) => `${px.toFixed(1)},${py.toFixed(1)}`)
          .join(" ")
      : null,
    thresholds: thresholds.map((threshold) => ({ ...threshold, y: y(threshold.cm) })),
    issued: report?.issued_at ? x(report.issued_at) : null,
    ticks,
    scale,
  };
}

const LEGEND = `<ul class="log-legend telemetry">
  <li><i class="ew-swatch ew-swatch-observed"></i>River level (cm)</li>
  <li><i class="ew-swatch ew-swatch-median"></i>Forecast median</li>
  <li><i class="ew-swatch ew-swatch-band"></i>10-90 % range</li>
  <li><i class="ew-swatch ew-swatch-threshold"></i>Selected community: at risk / cut off below</li>
</ul>`;

/** Draws the chart in `container`, to its width, for the `community` picked in the list (no thresholds without one). */
export function renderLevelForecastChart(container, report, { community = null } = {}) {
  const width = Math.round(container.clientWidth || 640);
  const scene = forecastScene(report, community, width);
  if (!scene) {
    container.innerHTML = "";
    return null;
  }
  const right = width - MARGIN.right;
  const text = (x, y, content, anchor = "start", extra = "") =>
    `<text class="log-text${extra}" x="${x.toFixed(1)}" y="${y.toFixed(1)}" text-anchor="${anchor}">${content}</text>`;
  const scale = scene.scale
    .map(
      (mark) =>
        `<line class="ew-grid" x1="${MARGIN.left}" x2="${right}" y1="${mark.y.toFixed(1)}" y2="${mark.y.toFixed(1)}"/>${text(width - 8, mark.y + 3, mark.cm, "end")}`,
    )
    .join("");
  const ticks = scene.ticks
    .map(
      (tick) =>
        `<line class="log-tick" x1="${tick.x.toFixed(1)}" x2="${tick.x.toFixed(1)}" y1="${scene.base}" y2="${scene.base + 4}"/>${text(tick.x + 3, scene.base + 16, tick.label)}`,
    )
    .join("");
  // Two close thresholds: the lower one's label goes under its line, so the labels never overlap
  const close = scene.thresholds.length === 2 && Math.abs(scene.thresholds[1].y - scene.thresholds[0].y) < 14;
  const thresholds = scene.thresholds
    .map((threshold, index) => {
      const labelY = close && index === 1 ? threshold.y + 12 : threshold.y - 4;
      return `<g class="ew-threshold ew-threshold-${threshold.kind}"><line x1="${MARGIN.left}" x2="${right}" y1="${threshold.y.toFixed(1)}" y2="${threshold.y.toFixed(1)}"/>${text(MARGIN.left + 4, labelY, `${escapeHtml(threshold.label)} ${threshold.cm} cm`)}</g>`;
    })
    .join("");
  const issued =
    scene.issued == null
      ? ""
      : `<line class="log-today" x1="${scene.issued.toFixed(1)}" x2="${scene.issued.toFixed(1)}" y1="${MARGIN.top}" y2="${scene.base}"/>${text(scene.issued + 4, MARGIN.top + 8, "forecast issued")}`;
  container.innerHTML = `<svg class="log-svg ew-chart" viewBox="0 0 ${width} ${CHART_HEIGHT}" width="100%" height="${CHART_HEIGHT}" role="img" aria-label="River level: observed, forecast and the selected community's thresholds">
      ${scale}<line class="log-base" x1="${MARGIN.left}" x2="${right}" y1="${scene.base}" y2="${scene.base}"/>${ticks}
      ${scene.band ? `<polygon class="ew-band" points="${scene.band}"/>` : ""}
      ${scene.observed ? `<path class="ew-observed" d="${scene.observed}"/>` : ""}
      ${scene.median ? `<path class="ew-median" d="${scene.median}"/>` : ""}
      ${thresholds}${issued}
    </svg>${LEGEND}`;
  return scene;
}
