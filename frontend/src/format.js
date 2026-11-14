const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "2026-09-09" -> "9 Sep 2026". Parsed by hand: no time zone can shift the day. */
export function formatDay(isoDate) {
  const [year, month, day] = isoDate.split("-").map(Number);
  return `${day} ${MONTHS[month - 1]} ${year}`;
}

/** Route width for display: "60 m", or an em dash when there is no route. */
export function formatWidth(meters) {
  return meters == null ? "—" : `${meters} m`;
}

/** River level in metres from the gauge's centimetres: 1180 -> "11.8 m". */
export function formatRiverLevel(centimeters) {
  return centimeters == null ? "—" : `${(centimeters / 100).toFixed(1)} m`;
}
