import { renderCommunityList, renderSelectedSummary } from "./components/communityList.js";
import { renderLayerControls } from "./components/layerControls.js";
import { renderRouteChart } from "./components/routeChart.js";
import { renderStatusFilter, toggleStatus } from "./components/statusFilter.js";
import { renderStatusPanel } from "./components/statusPanel.js";
import { formatDay, formatRiverLevel } from "./format.js";
import { imageryChoices, layersToShow } from "./imagery.js";
import { loadHistory, loadOverview, loadPass } from "./services/api.js";
import { createMap } from "./services/mapService.js";
import { STATUSES, bySeverity, countByStatus, statusOf } from "./status.js";
import { currentTheme, toggleTheme } from "./theme.js";

const $ = (selector) => document.querySelector(selector);
const elements = {
  list: $("#community-list"),
  summary: $("#selected-summary"),
  status: $("#status-content"),
  chart: $("#route-chart"),
  themeToggle: $("#theme-toggle"),
  layers: $("#layer-controls"),
  filter: $("#status-filter"),
};

// Everything the page shows comes from here: the area's overview, the pass on screen and the user's choices
const state = {
  overview: null,
  live: false,
  passId: null,
  passFailed: false,
  communities: [],
  imagery: [],
  choices: { radar: null, opacity: 1, overlays: {} },
  basemap: "map",
  visible: new Set(Object.keys(STATUSES)),
  unavailable: new Set(),
  selectedId: null,
  chartHistory: null,
};
let mapControls;

const thresholdM = () => state.overview?.area?.connectedMinWidthM ?? null;
const hubName = () => state.overview?.hub?.name ?? state.overview?.area?.name ?? "the hub";
const shownCommunities = () => state.communities.filter((community) => state.visible.has(statusOf(community)));

function drawChart() {
  renderRouteChart(elements.chart, state.chartHistory, { theme: currentTheme(), thresholdM: thresholdM() });
}

function renderList() {
  renderCommunityList(elements.list, shownCommunities(), state.selectedId, select);
}

/** The selected community as the pass on screen saw it. */
function renderSelection() {
  const community = state.communities.find((item) => item.id === state.selectedId);
  if (!community) return;
  renderSelectedSummary(elements.summary, community);
  renderStatusPanel(elements.status, community, { thresholdM: thresholdM(), hubName: hubName() });
}

async function select(id) {
  if (!state.communities.some((item) => item.id === id)) return;
  state.selectedId = id;
  renderList();
  renderSelection();
  mapControls?.selectCommunity(id);

  const history = await loadHistory(id, state.live);
  // The user may have picked another community while this one loaded
  if (state.selectedId === id) {
    state.chartHistory = history;
    drawChart();
  }
}

/** The figures of the pass on screen. */
function renderStats() {
  const { overview } = state;
  const counts = countByStatus(state.communities);
  const pad = (count) => String(count).padStart(2, "0");
  const pass =
    overview.passes.find((item) => item.id === state.passId) ??
    (overview.latestPass?.id === state.passId ? overview.latestPass : null);
  $("#stat-communities").textContent = pad(state.communities.length);
  $("#stat-isolated").textContent = pad(counts.isolated);
  $("#stat-at-risk").textContent = pad(counts.at_risk);
  $("#stat-pass").textContent = pass ? formatDay(pass.acquiredOn) : "—";
  $("#stat-river").textContent =
    pass?.riverLevelCm == null
      ? pass
        ? "River level unknown"
        : "No pass yet"
      : `River at ${formatRiverLevel(pass.riverLevelCm)}`;
  $("#data-mode").textContent = state.live ? "LIVE DATA" : "DEMO DATA";
}

function renderFilter() {
  if (!elements.filter) return;
  renderStatusFilter(elements.filter, state.communities, state.visible, (status) => {
    state.visible = toggleStatus(state.visible, status);
    renderFilter();
    renderList();
    mapControls?.setVisibleStatuses(state.visible);
  });
}

function applyImagery() {
  mapControls?.showImagery(layersToShow(state.choices, state.imagery));
}

function renderControls() {
  if (!elements.layers) return;
  renderLayerControls(
    elements.layers,
    {
      passes: state.overview.passes,
      passId: state.passId,
      basemap: state.basemap,
      imagery: state.imagery,
      choices: state.choices,
      unavailable: state.unavailable,
      live: state.live,
      failed: state.passFailed,
    },
    {
      onPass: changePass,
      onBasemap: (basemap) => {
        state.basemap = basemap;
        mapControls?.setBasemap(basemap);
        renderControls();
      },
      onRadar: (radar) => {
        state.choices = { ...state.choices, radar };
        applyImagery();
        renderControls();
      },
      // No redraw of the panel: the slider keeps its grip while it is dragged
      onOpacity: (opacity) => {
        state.choices = { ...state.choices, opacity };
        applyImagery();
      },
      onOverlay: (id, visible) => {
        state.choices = { ...state.choices, overlays: { ...state.choices.overlays, [id]: visible } };
        applyImagery();
      },
    },
  );
}

/** An image the API failed to serve: the panel says so, the rest of the map carries on. */
function imageFailed(layerId) {
  if (state.unavailable.has(layerId)) return;
  state.unavailable = new Set([...state.unavailable, layerId]);
  renderControls();
}

/** Shows another pass: its communities, figures and imagery, keeping the user's layer choices. */
async function changePass(passId) {
  state.passId = passId;
  const pass = await loadPass(passId, state.live, state.overview.layerCatalog);
  // The user may have picked another pass while this one loaded
  if (state.passId !== passId) return;
  state.passFailed = !pass;
  if (pass) {
    state.communities = bySeverity(pass.communities);
    state.imagery = pass.imagery;
    state.unavailable = new Set();
    state.choices = imageryChoices(state.imagery, state.choices);
    renderFilter();
    renderList();
    renderSelection();
    mapControls?.setCommunities(state.communities);
    applyImagery();
  }
  renderStats();
  renderControls();
}

/** The area's name wherever the page says it. */
function applyArea(area) {
  if (!area?.name) return;
  document.querySelectorAll("[data-area-name]").forEach((element) => (element.textContent = area.name));
  document.querySelectorAll("[data-area-region]").forEach((element) => (element.textContent = area.region ?? ""));
  document.title = `River Lifeline | ${area.name}`;
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
    if (state.chartHistory) drawChart();
  });
}

/** A button that shows and hides the panel body it controls (`aria-controls`). */
function setUpPanelToggle(button, open) {
  const body = button && document.getElementById(button.getAttribute("aria-controls"));
  if (!body) return;
  const show = (visible) => {
    button.setAttribute("aria-expanded", String(visible));
    body.hidden = !visible;
  };
  show(open);
  button.addEventListener("click", () => show(button.getAttribute("aria-expanded") !== "true"));
}

async function init() {
  setUpThemeToggle();
  // Both panels start folded, so the map shows first; a soft periodic nudge on their toggles (CSS) invites a click
  setUpPanelToggle($("#communities-toggle"), false);
  setUpPanelToggle($("#layers-toggle"), false);

  const { overview, live } = await loadOverview();
  state.overview = overview;
  state.live = live;
  state.passId = overview.passId;
  state.communities = bySeverity(overview.communities);
  state.imagery = overview.imagery;
  state.choices = imageryChoices(overview.imagery);
  applyArea(overview.area);
  renderStats();
  renderFilter();
  renderControls();
  // The community most in need first
  select(state.communities[0]?.id);

  createMap({
    area: overview.area,
    hub: overview.hub,
    communities: state.communities,
    onSelect: select,
    theme: currentTheme(),
    onImageError: imageFailed,
  })
    .then((controls) => {
      mapControls = controls;
      // Catch up with whatever the user changed while the map was being laid out
      controls.setCommunities(state.communities);
      controls.setVisibleStatuses(state.visible);
      controls.setBasemap(state.basemap);
      // No flight to the first community: the map opens on the whole area
      applyImagery();
    })
    .catch((error) => {
      $("#map").innerHTML = '<p class="map-error">Map could not load. Check your connection and reload.</p>';
      console.error("Map initialisation failed:", error);
    });
}

init();
