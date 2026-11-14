import { formatDay } from "../format.js";
import { CONNECTED_MIN_WIDTH_M, STATUSES } from "../status.js";

let chart;

/** Width (m) to plot for an assessment: 0 once isolated, a gap when the pass had no data. */
export function plottedWidth(assessment) {
  if (assessment.status === "isolated") return 0;
  return assessment.routeWidthM ?? null;
}

/** The narrowest point of the community's route on every NISAR pass, against the connected threshold. */
export function renderRouteChart(canvas, history) {
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
          borderColor: "#72b8d0",
          backgroundColor: "#72b8d018",
          pointBackgroundColor: assessments.map((assessment) => STATUSES[assessment.status].color),
          pointRadius: 4,
          borderWidth: 2,
          tension: 0.25,
          spanGaps: false,
        },
        {
          label: `Connected (${CONNECTED_MIN_WIDTH_M} m)`,
          data: assessments.map(() => CONNECTED_MIN_WIDTH_M),
          borderColor: "#e4b65c",
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
        tooltip: { backgroundColor: "#172530", titleColor: "#edf4f7", bodyColor: "#c1d0d8", padding: 10 },
      },
      scales: {
        x: {
          grid: { display: false },
          ticks: { color: "#8fa2ae", maxRotation: 0, autoSkip: true, maxTicksLimit: 6 },
          border: { display: false },
        },
        y: {
          beginAtZero: true,
          grid: { color: "#263742" },
          ticks: { color: "#8fa2ae", maxTicksLimit: 5 },
          border: { display: false },
          title: { display: true, text: "m", color: "#8fa2ae" },
        },
      },
    },
  });
}
