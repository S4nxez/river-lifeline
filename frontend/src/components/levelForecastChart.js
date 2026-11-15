// The river level behind the early warning: what the gauge read, the median forecast and its 10-90 % band, against
// the levels below which the selected community is at risk or cut off.
import { formatDay } from "../format.js";
import { CHART_COLORS } from "./routeChart.js";

let chart;

// The early warning's own colours: never the status colours of the main view
export const FORECAST_COLORS = {
  light: { median: "#6d4ac7", band: "#6d4ac71f", atRisk: "#a48ad6", isolated: "#3d2489" },
  dark: { median: "#b49cff", band: "#b49cff24", atRisk: "#8e78d6", isolated: "#e2d9ff" },
};

/** The datasets of the chart, by date: observed level, p10 and p90 (the band between them), p50, thresholds. */
export function forecastDatasets(report, community, theme = "light") {
  const colors = CHART_COLORS[theme] ?? CHART_COLORS.light;
  const own = FORECAST_COLORS[theme] ?? FORECAST_COLORS.light;
  const observed = new Map((report?.observed_level ?? []).map((row) => [row.date, row.level_cm]));
  const forecast = new Map((report?.level_forecast ?? []).map((row) => [row.date, row]));
  const labels = [...new Set([...observed.keys(), ...forecast.keys()])].sort();
  const at = (map, pick) => labels.map((day) => (map.has(day) ? pick(map.get(day)) : null));
  const threshold = (value, label, color, dash) =>
    value == null
      ? []
      : [
          {
            label,
            data: labels.map(() => value),
            borderColor: color,
            borderDash: dash,
            borderWidth: 1.5,
            pointRadius: 0,
          },
        ];
  return {
    labels,
    datasets: [
      {
        label: "Observed level",
        data: at(observed, (v) => v),
        borderColor: colors.line,
        borderWidth: 2,
        pointRadius: 0,
      },
      { label: "Forecast 10 %", data: at(forecast, (q) => q.p10_cm), borderColor: "transparent", pointRadius: 0 },
      {
        label: "Forecast 10-90 %",
        data: at(forecast, (q) => q.p90_cm),
        borderColor: "transparent",
        backgroundColor: own.band,
        fill: "-1",
        pointRadius: 0,
      },
      {
        label: "Forecast median",
        data: at(forecast, (q) => q.p50_cm),
        borderColor: own.median,
        borderDash: [4, 3],
        borderWidth: 2,
        pointRadius: 0,
      },
      ...threshold(community?.at_risk_below_cm, "At risk below", own.atRisk, [6, 4]),
      ...threshold(community?.isolated_below_cm, "Cut off below", own.isolated, [2, 3]),
    ],
  };
}

export function renderLevelForecastChart(canvas, report, { community = null, theme = "light" } = {}) {
  const colors = CHART_COLORS[theme] ?? CHART_COLORS.light;
  if (chart) chart.destroy();
  const { labels, datasets } = forecastDatasets(report, community, theme);
  chart = new Chart(canvas, {
    type: "line",
    data: { labels: labels.map(formatDay), datasets },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: "index", intersect: false },
      plugins: {
        legend: { display: false },
        tooltip: {
          backgroundColor: colors.tooltip.background,
          titleColor: colors.tooltip.title,
          bodyColor: colors.tooltip.body,
          borderColor: colors.tooltip.border,
          borderWidth: 1,
          filter: (item) => item.raw != null && item.dataset.label !== "Forecast 10 %",
        },
      },
      scales: {
        x: {
          grid: { display: false },
          ticks: { color: colors.ticks, maxRotation: 0, autoSkip: true, maxTicksLimit: 6 },
          border: { display: false },
        },
        y: {
          grid: { color: colors.grid },
          ticks: { color: colors.ticks, maxTicksLimit: 6 },
          border: { display: false },
          title: { display: true, text: "cm at the gauge", color: colors.ticks },
        },
      },
    },
  });
  return chart;
}
