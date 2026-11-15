// The page: one area, one NISAR pass at a time. It loads the overview, keeps the user's choices in `state` and
// redraws the parts that depend on them. Every part reads from `state`; the map is driven through its controls.
import { expectedPasses, neighbourPass, passForDay, renderClock } from "./components/clock.js";
import { filterCommunities, renderCommunityList, renderListFilters } from "./components/communityList.js";
import { renderDossier } from "./components/dossier.js";
import { renderEarlyWarningPanel, renderLimits } from "./components/earlyWarningPanel.js";
import { renderHeadline } from "./components/headline.js";
import { renderPlate } from "./components/imagePlate.js";
import { renderLayerControls } from "./components/layerControls.js";
import { renderLedger } from "./components/ledger.js";
import { renderLevelForecastChart } from "./components/levelForecastChart.js";
import { renderMapKey } from "./components/mapKey.js";
import { renderPassLog } from "./components/passLog.js";
import { formatDay, formatShortDay, formatUtcTime } from "./format.js";
import { readHash, writeHash } from "./hashState.js";
import { imageryChoices, layersToShow } from "./imagery.js";
import { exportUrl, loadEarlyWarning, loadHistory, loadOverview, loadPass, loadRiverLevel } from "./services/api.js";
import { createMap } from "./services/mapService.js";
import { bySeverity } from "./status.js";
import { currentTheme, toggleTheme } from "./theme.js";

const PLAY_STEP_MS = 1300;
const ARROWS = { ascending: "↑", descending: "↓" };

const $ = (selector) => document.querySelector(selector);

// Everything the page shows comes from here: the area's overview, the pass on screen and the user's choices
const state = {
  overview: null,
  live: false,
  passId: null,
  day: null,
  passFailed: false,
  communities: [],
  imagery: [],
  plate: null,
  river: null,
  choices: { radar: null, opacity: 1, overlays: {} },
  basemap: "map",
  filter: "all",
  query: "",
  unavailable: new Set(),
  selectedId: null,
  history: null,
  playing: false,
  logAnimated: false,
};
let mapControls;
// The early warning: its own section at the bottom, never read by the rest of the page
const earlyWarning = { report: null, selectedId: null };
let playTimer = null;

const today = () => new Date().toISOString().slice(0, 10);
const area = () => state.overview?.area ?? null;
const hubName = () => state.overview?.hub?.name ?? area()?.name ?? "the hub";
const passes = () => state.overview?.passes ?? [];
const classes = () => area()?.widthClassesM ?? [];

/** The pass on screen; when the pass list couldn't be loaded, what the communities say about it. */
function currentPass() {
  const listed = passes().find((pass) => pass.id === state.passId);
  if (listed) return listed;
  const day = state.communities.find((community) => community.latest?.acquiredOn)?.latest.acquiredOn;
  return day ? { id: state.passId, acquiredOn: day, evaluated: true, counts: null, riverLevelCm: null } : null;
}

const previousPass = () => neighbourPass(passes(), state.passId, -1);
const shownCommunities = () => filterCommunities(state.communities, { filter: state.filter, query: state.query });
const selectedCommunity = () => state.communities.find((community) => community.id === state.selectedId) ?? null;

function renderHeadlineBand() {
  renderHeadline($("#headline"), {
    pass: currentPass(),
    communities: state.communities,
    hubName: hubName(),
    thresholdM: area()?.connectedMinWidthM ?? null,
    day: state.day,
  });
}

function renderTimeControls() {
  renderClock(
    $("#clock"),
    { passes: passes(), passId: state.passId, day: state.day ?? today(), today: today(), playing: state.playing },
    {
      onDay: (day) => {
        stopPlaying();
        showDay(day);
      },
      onStep: (step) => {
        stopPlaying();
        const next = neighbourPass(passes(), state.passId, step);
        if (next) changePass(next.id);
      },
      onPlay: togglePlay,
    },
  );
}

/** "Next pass over the area": when NISAR is next due back, from the tracks that covered it. */
function renderNextPass() {
  const target = $("#next-pass");
  if (!target) return;
  const next = expectedPasses(passes(), today())[0];
  target.hidden = !next;
  if (!next) return;
  const track = next.track == null ? "" : ` · track ${next.track} ${ARROWS[next.orbitDirection] ?? ""}`.trimEnd();
  $("#next-pass-value").textContent =
    next.day === today() ? "acquired today, published within days" : `${formatDay(next.day)}${track}`;
}

function renderList() {
  const shown = shownCommunities();
  const pass = currentPass();
  renderCommunityList(
    $("#community-list"),
    {
      communities: shown,
      selectedId: state.selectedId,
      classesM: classes(),
      dayLabel: pass ? formatShortDay(pass.acquiredOn) : "",
    },
    select,
  );
  $("#list-count").textContent = `${shown.length} of ${state.communities.length}`;
  const filtering = state.filter !== "all" || state.query.trim() !== "";
  mapControls?.setVisibleIds(filtering ? shown.map((community) => community.id) : null);
}

function renderFilters() {
  renderListFilters($("#list-filters"), state.filter, (filter) => {
    state.filter = filter;
    renderFilters();
    renderList();
  });
}

/** "Download this day": the backend writes the file of the pass on screen. Live data only. */
function renderDownload() {
  const block = $("#list-download");
  if (!block) return;
  block.hidden = !state.live || !state.passId;
  if (block.hidden) return;
  for (const format of ["csv", "geojson"]) {
    const link = block.querySelector(`[data-format="${format}"]`);
    if (link) link.href = exportUrl(state.passId, format);
  }
}

function renderDesk() {
  renderLedger($("#ledger"), {
    pass: currentPass(),
    previous: previousPass(),
    communities: state.communities,
    gaugeName: area()?.gauge?.name ?? null,
  });
  renderDossier($("#dossier"), {
    community: selectedCommunity(),
    history: state.history?.id === state.selectedId ? state.history : null,
    passes: passes(),
    passId: state.passId,
    hubName: hubName(),
    classesM: classes(),
  });
}

function drawPassLog() {
  const container = $("#pass-log");
  if (!container) return;
  // The "downlink" entrance plays once, and only for someone watching who hasn't asked for less motion
  const animate =
    !state.logAnimated &&
    globalThis.document?.visibilityState === "visible" &&
    !(globalThis.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches ?? false);
  state.logAnimated = true;
  renderPassLog(
    container,
    {
      passes: passes(),
      passId: state.passId,
      river: state.river,
      today: today(),
      expected: expectedPasses(passes(), today()),
      animate,
    },
    (passId) => {
      stopPlaying();
      changePass(passId);
    },
  );
}

/** The early warning section: its list, and the level chart of the community picked in it. */
function renderEarlyWarning() {
  const { report } = earlyWarning;
  const content = $("#early-warning-content");
  if (!report || !content) return;
  const listed = report.communities.filter((item) => item.class === "monitored");
  if (!listed.some((item) => item.community_id === earlyWarning.selectedId)) {
    earlyWarning.selectedId = null;
  }
  const names = Object.fromEntries(state.communities.map((community) => [community.id, community.name]));
  const shown = renderEarlyWarningPanel(content, report, {
    names,
    selectedId: earlyWarning.selectedId,
    onSelect: (id) => {
      earlyWarning.selectedId = id;
      renderEarlyWarning();
    },
  });
  if (!earlyWarning.selectedId && shown.length) {
    earlyWarning.selectedId = shown[0].community_id;
    renderEarlyWarning();
    return;
  }
  const community = report.communities.find((item) => item.community_id === earlyWarning.selectedId) ?? null;
  renderLevelForecastChart($("#level-forecast-chart"), report, { community });
}

/** Shows the early warning section only when the API has one; nothing else on the page depends on it. */
async function setUpEarlyWarning() {
  const section = $("#early-warning");
  if (!section) return;
  const report = await loadEarlyWarning();
  if (!report) return;
  earlyWarning.report = report;
  section.hidden = false;
  renderLimits($("#early-warning-limits"), report);
  renderEarlyWarning();
}

function applyImagery() {
  mapControls?.showImagery(layersToShow(state.choices, state.imagery));
  renderPlate($("#image-plate"), state.plate, state.choices.radar);
}

function renderControls() {
  renderLayerControls(
    $("#layer-controls"),
    {
      passes: passes(),
      passId: state.passId,
      basemap: state.basemap,
      imagery: state.imagery,
      choices: state.choices,
      unavailable: state.unavailable,
      live: state.live,
      failed: state.passFailed,
    },
    {
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

function saveView() {
  writeHash({ day: state.day, communityId: state.selectedId });
}

/** Everything that depends on the pass on screen. */
function renderPassViews() {
  renderHeadlineBand();
  renderTimeControls();
  renderList();
  renderDownload();
  renderDesk();
  drawPassLog();
  renderControls();
}

/** Picks a community: the list, the map and the dossier follow, and its card loads for the history. */
async function select(id, { fly = true } = {}) {
  if (!state.communities.some((community) => community.id === id)) return;
  state.selectedId = id;
  renderList();
  renderDesk();
  if (earlyWarning.report?.communities.some((item) => item.community_id === id && item.class === "monitored")) {
    earlyWarning.selectedId = id;
    renderEarlyWarning();
  }
  mapControls?.selectCommunity(id, { fly });
  saveView();

  const history = await loadHistory(id, state.live);
  // The user may have picked another community while this one loaded
  if (state.selectedId === id) {
    state.history = history ? { ...history, id } : null;
    renderDesk();
  }
}

/** Shows another pass: its communities, figures and imagery, keeping the user's layer choices and selection. */
async function changePass(passId, { day = null } = {}) {
  state.passId = passId;
  const pass = passes().find((item) => item.id === passId);
  state.day = day ?? pass?.acquiredOn ?? state.day;
  renderTimeControls();
  const loaded = await loadPass(passId, state.live, state.overview.layerCatalog);
  // The user may have picked another pass while this one loaded
  if (state.passId !== passId) return;
  state.passFailed = !loaded;
  if (loaded) {
    state.communities = bySeverity(loaded.communities);
    state.imagery = loaded.imagery;
    state.plate = loaded.plate;
    state.unavailable = new Set();
    state.choices = imageryChoices(state.imagery, state.choices);
    mapControls?.setCommunities(state.communities);
    mapControls?.selectCommunity(state.selectedId, { fly: false });
    applyImagery();
  }
  renderPassViews();
  saveView();
}

/** Shows the pass a day falls on (the latest one on or before it). */
function showDay(day) {
  const pass = passForDay(passes(), day);
  if (pass) changePass(pass.id, { day });
}

function stopPlaying() {
  if (playTimer) clearInterval(playTimer);
  playTimer = null;
  if (state.playing) {
    state.playing = false;
    renderTimeControls();
  }
}

/** Play record: one pass every 1.3 s from the one on screen (from the first, if it is the last), until the last. */
function togglePlay() {
  if (state.playing) {
    stopPlaying();
    return;
  }
  if (!neighbourPass(passes(), state.passId, 1)) {
    const first = passes().find((pass) => pass.evaluated);
    if (first) changePass(first.id);
  }
  state.playing = true;
  renderTimeControls();
  playTimer = setInterval(() => {
    const next = neighbourPass(passes(), state.passId, 1);
    if (next) changePass(next.id);
    if (!next || !neighbourPass(passes(), next.id, 1)) stopPlaying();
  }, PLAY_STEP_MS);
}

/** The area's names and sources wherever the page says them. */
function applyArea() {
  const { overview } = state;
  const names = {
    "[data-area-name]": area()?.name,
    "[data-hub-name]": hubName(),
    "[data-gauge-name]": area()?.gauge?.name,
    "[data-gauge-source]": [area()?.gauge?.provider, area()?.gauge?.name].filter(Boolean).join(", "),
  };
  for (const [selector, text] of Object.entries(names)) {
    if (!text) continue;
    document.querySelectorAll(selector).forEach((element) => (element.textContent = text));
  }
  if (area()?.name) document.title = `River Lifeline | ${area().name}`;
  const mode = $("#data-mode");
  if (mode) mode.textContent = state.live ? "Live record" : "Demo data";
  const updated = $("#record-updated");
  if (updated) {
    const stamp = overview.recordUpdatedAt;
    updated.hidden = !stamp;
    if (stamp) updated.textContent = `Record updated ${stamp.slice(0, 10)} ${formatUtcTime(stamp) ?? ""}`.trim();
  }
  renderMapKey($("#map-key"), hubName());
}

/** The light/dark switch: the page and the basemap follow it. */
function setUpThemeToggle() {
  const toggle = $("#theme-toggle");
  if (!toggle) return;
  const sync = () => toggle.setAttribute("aria-checked", String(currentTheme() === "dark"));
  sync();
  toggle.addEventListener("click", () => {
    const theme = toggleTheme();
    sync();
    mapControls?.setTheme(theme);
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

function setUpSearch() {
  const field = $("#list-search");
  field?.addEventListener("input", () => {
    state.query = field.value;
    renderList();
  });
}

/** The pass log is drawn to its width: redraw it when the window settles on a new size. */
function setUpResize() {
  let timer = null;
  globalThis.addEventListener?.("resize", () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      drawPassLog();
      renderEarlyWarning();
    }, 150);
  });
}

async function init() {
  setUpThemeToggle();
  // The layers start folded, so the map shows first; a soft periodic nudge on their toggle (CSS) invites a click
  setUpPanelToggle($("#layers-toggle"), false);
  setUpSearch();
  setUpResize();

  const [{ overview, live }, river] = await Promise.all([loadOverview(), loadRiverLevel(true).catch(() => null)]);
  state.overview = overview;
  state.live = live;
  state.river = live ? river : await loadRiverLevel(false);
  state.passId = overview.passId;
  state.communities = bySeverity(overview.communities);
  state.imagery = overview.imagery;
  state.plate = overview.plate;
  state.choices = imageryChoices(overview.imagery);
  state.day = currentPass()?.acquiredOn ?? null;
  applyArea();
  renderFilters();
  renderNextPass();
  renderPassViews();
  setUpEarlyWarning();

  createMap({
    area: overview.area,
    hub: overview.hub,
    communities: state.communities,
    onSelect: (id) => select(id, { fly: false }),
    theme: currentTheme(),
    onImageError: imageFailed,
  })
    .then((controls) => {
      mapControls = controls;
      // Catch up with whatever the user changed while the map was being laid out
      controls.setCommunities(state.communities);
      controls.setBasemap(state.basemap);
      renderList();
      if (state.selectedId) controls.selectCommunity(state.selectedId, { fly: false });
      applyImagery();
    })
    .catch((error) => {
      $("#map").innerHTML = '<p class="map-error">Map could not load. Check your connection and reload.</p>';
      console.error("Map initialisation failed:", error);
    });

  // A shared link opens the same view: its day, then its community
  const asked = readHash();
  if (asked.day && asked.day !== state.day) {
    const pass = passForDay(passes(), asked.day);
    if (pass) await changePass(pass.id, { day: asked.day });
  }
  if (asked.communityId) select(asked.communityId);
}

init();
