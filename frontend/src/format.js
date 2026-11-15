// How the page writes dates, widths, levels and changes. Dates are UTC days, written the British English way.

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const DAY_MS = 86_400_000;
// The typographic minus, for changes: "−3", not "-3"
const MINUS = "−";

/** "2026-09-09" -> "9 Sep 2026". Parsed by hand: no time zone can shift the day. */
export function formatDay(isoDate) {
  const [year, month, day] = isoDate.split("-").map(Number);
  return `${day} ${MONTHS[month - 1]} ${year}`;
}

/** "2026-09-09" -> "9 Sep". */
export function formatShortDay(isoDate) {
  const [, month, day] = isoDate.split("-").map(Number);
  return `${day} ${MONTHS[month - 1]}`;
}

/** "2026-10-30T22:41:00Z" -> "22:41 UTC"; null without a time. */
export function formatUtcTime(timestamp) {
  const match = /T(\d{2}):(\d{2})/.exec(timestamp ?? "");
  return match ? `${match[1]}:${match[2]} UTC` : null;
}

/** The month label of a time axis: "Mar", or "Jan 2027" when a year starts. */
export function formatMonthTick(isoDate) {
  const [year, month] = isoDate.split("-").map(Number);
  return month === 1 ? `Jan ${year}` : MONTHS[month - 1];
}

/** Whole days from `fromDay` to `toDay` ("YYYY-MM-DD"), negative when `toDay` comes first. */
export function daysBetween(fromDay, toDay) {
  return Math.round((Date.parse(`${toDay}T00:00:00Z`) - Date.parse(`${fromDay}T00:00:00Z`)) / DAY_MS);
}

/** The day `days` after `isoDate` ("YYYY-MM-DD"). */
export function addDays(isoDate, days) {
  return new Date(Date.parse(`${isoDate}T00:00:00Z`) + days * DAY_MS).toISOString().slice(0, 10);
}

/** Numbers with English thousands separators and at most one decimal: 1234.56 -> "1,234.6". */
export function formatNumber(value) {
  return value.toLocaleString("en-GB", { maximumFractionDigits: 1 });
}

/** A change, signed with a typographic minus: "+3", "−132 cm", or "no change" when it rounds to nothing. */
export function formatChange(value, unit = "") {
  const rounded = Math.round(value * 10) / 10;
  if (rounded === 0) return "no change";
  const sign = rounded > 0 ? "+" : MINUS;
  return `${sign}${formatNumber(Math.abs(rounded))}${unit ? ` ${unit}` : ""}`;
}

/** River level in centimetres for display: 797 -> "797 cm". */
export function formatLevel(centimetres) {
  return centimetres == null ? "—" : `${formatNumber(centimetres)} cm`;
}

// Why a pass gives no width, by the API's reason codes: no route found, or a route it couldn't verify
const NO_ROUTE_REASONS = new Set(["no_route_confirmed", "no_route_unconfirmed"]);
const UNVERIFIED_REASONS = new Set(["route_unverified", "route_through_nodata"]);

/**
 * The width class of a route, from the narrowest width it still passes (`widthM`, one of the area's tested widths, or
 * 0 below the first) and the area's `classesM`: "< 40 m", "40–60 m", "≥ 400 m". Without a width it says why, from
 * the status and the API's `reason`: "no route", "not verified", or an em dash (not seen).
 */
export function widthClassLabel(widthM, status, classesM = [], reason = null) {
  if (widthM == null) {
    if (status === "isolated" || NO_ROUTE_REASONS.has(reason)) return "no route";
    if (UNVERIFIED_REASONS.has(reason)) return "not verified";
    return "—";
  }
  const classes = [...classesM].sort((a, b) => a - b);
  if (classes.length === 0) return `${widthM} m`;
  if (widthM < classes[0]) return `< ${classes[0]} m`;
  const index = classes.indexOf(widthM);
  if (index === -1) return `${widthM} m`;
  return index === classes.length - 1 ? `≥ ${widthM} m` : `${widthM}–${classes[index + 1]} m`;
}

/** Text from the data, safe to put in HTML. */
export function escapeHtml(text) {
  return String(text).replace(
    /[&<>"']/g,
    (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char],
  );
}
