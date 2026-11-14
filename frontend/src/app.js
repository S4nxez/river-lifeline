import { renderCommunityList, renderSelectedSummary } from "./components/communityList.js";
import { renderRouteChart } from "./components/routeChart.js";
import { renderStatusPanel } from "./components/statusPanel.js";
import { formatDay, formatRiverLevel } from "./format.js";
import { loadHistory, loadOverview } from "./services/api.js";
import { createMap } from "./services/mapService.js";
import { bySeverity, countByStatus } from "./status.js";
import { currentTheme, toggleTheme } from "./theme.js";

const $ = (selector) => document.querySelector(selector);
const elements = {
  list: $("#community-list"),
  summary: $("#selected-summary"),
  status: $("#status-content"),
  chart: $("#route-chart"),
  themeToggle: $("#theme-toggle"),
};

let communities = [];
let live = false;
let selectedId = null;
let mapControls;
// The route history on the chart, to redraw it in the other theme
let chartHistory = null;

async function select(id) {
  const community = communities.find((item) => item.id === id);
  if (!community) return;
  selectedId = id;
  renderCommunityList(elements.list, communities, selectedId, select);
  renderSelectedSummary(elements.summary, community);
  renderStatusPanel(elements.status, community);
  mapControls?.selectCommunity(id);

  const history = await loadHistory(id, live);
  // The user may have picked another community while this one loaded
  if (selectedId === id) {
    chartHistory = history;
    renderRouteChart(elements.chart, history, currentTheme());
  }
}

/** The light/dark switch: the page, the basemap and the chart follow it. */
function setUpThemeToggle() {
  const toggle = elements.themeToggle;
  if (!toggle) return;
  const sync = () => toggle.setAttribute("aria-checked", String(currentTheme() === "dark"));
  sync();
  toggle.addEventListener("click", () => {
    const theme = toggleTheme();
    sync();
    mapControls?.setTheme(theme);
    if (chartHistory) renderRouteChart(elements.chart, chartHistory, theme);
  });
}

function renderStats(overview) {
  const counts = countByStatus(communities);
  const pad = (count) => String(count).padStart(2, "0");
  $("#stat-communities").textContent = pad(communities.length);
  $("#stat-isolated").textContent = pad(counts.isolated);
  $("#stat-at-risk").textContent = pad(counts.at_risk);
  const pass = overview.latestPass;
  $("#stat-pass").textContent = pass ? formatDay(pass.acquiredOn) : "—";
  $("#stat-river").textContent = pass ? `River at ${formatRiverLevel(pass.riverLevelCm)}` : "No pass yet";
  $("#data-mode").textContent = live ? "LIVE DATA" : "DEMO DATA";
}

async function init() {
  setUpThemeToggle();
  const loaded = await loadOverview();
  live = loaded.live;
  communities = bySeverity(loaded.overview.communities);
  renderStats(loaded.overview);
  // The community most in need first
  select(communities[0]?.id);

  createMap(loaded.overview.hub, communities, select, currentTheme())
    .then((controls) => {
      mapControls = controls;
      mapControls.selectCommunity(selectedId);
    })
    .catch((error) => {
      $("#map").innerHTML = '<p class="map-error">Map could not load. Check your connection and reload.</p>';
      console.error("Map initialisation failed:", error);
    });
}

init();
