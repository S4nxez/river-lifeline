import { formatDay } from "../format.js";
import { CONNECTED_MIN_WIDTH_M, STATUSES } from "../status.js";

let chart;

// Chart.js draws on a canvas, so it can't read the CSS: these mirror the theme tokens of main.css
export const CHART_COLORS = {
  light: {
    line: "#111214",
    fill: "#11121410",
    point: "#ffffff",
    threshold: "#8a8f96",
    grid: "#ececea",
    ticks: "#5c6066",
    tooltip: { background: "#ffffff", title: "#111214", body: "#5c6066", border: "#e6e6e3" },
  },
  dark: {
    line: "#ececec",
    fill: "#ececec14",
    point: "#141517",
    threshold: "#7d8188",
    grid: "#26282c",
    ticks: "#a1a5ab",
    tooltip: { background: "#1c1d20", title: "#ececec", body: "#a1a5ab", border: "#2e3034" },
  },
};

/** Width (m) to plot for an assessment: 0 once isolated, a gap when the pass had no data. */
export function plottedWidth(assessment) {
  if (assessment.status === "isolated") return 0;
  return assessment.routeWidthM ?? null;
}

/** The narrowest point of the community's route on every NISAR pass, against the connected threshold. */
export function renderRouteChart(canvas, history, theme = "light") {
  const colors = CHART_COLORS[theme] ?? CHART_COLORS.light;
  if (chart) chart.destroy();
  const assessments = history?.assessments ?? [];
  chart = new Chart(canvas, {
    type: "line",
    data: {
      labels: assessments.map((assessment) => formatDay(assessment.acquiredOn)),
      datasets: [
        {
          label: "Route width",
          data: assessments.map(plottedWidth),
          borderColor: colors.line,
          backgroundColor: colors.fill,
          pointBorderColor: colors.point,
          pointBackgroundColor: assessments.map((assessment) => STATUSES[assessment.status].color),
          pointRadius: 4,
          borderWidth: 2,
          tension: 0.25,
          spanGaps: false,
        },
        {
          label: `Connected (${CONNECTED_MIN_WIDTH_M} m)`,
          data: assessments.map(() => CONNECTED_MIN_WIDTH_M),
          borderColor: colors.threshold,
          borderDash: [6, 4],
          borderWidth: 1.5,
          pointRadius: 0,
        },
      ],
    },
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
          padding: 10,
        },
      },
      scales: {
        x: {
          grid: { display: false },
          ticks: { color: colors.ticks, maxRotation: 0, autoSkip: true, maxTicksLimit: 6 },
          border: { display: false },
        },
        y: {
          beginAtZero: true,
          grid: { color: colors.grid },
          ticks: { color: colors.ticks, maxTicksLimit: 5 },
          border: { display: false },
          title: { display: true, text: "m", color: colors.ticks },
        },
      },
    },
  });
}
