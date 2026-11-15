// The day and the community on screen live in the URL hash (`#d=2026-10-23&c=<id>`), so a link opens the same view.

const DAY = /^\d{4}-\d{2}-\d{2}$/;

/** The `day` and `communityId` a hash asks for; each null when absent or malformed. */
export function readHash(hash = globalThis.location?.hash ?? "") {
  const params = new URLSearchParams(hash.replace(/^#/, ""));
  const day = params.get("d");
  const communityId = params.get("c");
  return { day: day && DAY.test(day) ? day : null, communityId: communityId || null };
}

/** The hash for a view: `#d=...&c=...`, leaving out what isn't set. */
export function hashFor({ day = null, communityId = null }) {
  const params = new URLSearchParams();
  if (day) params.set("d", day);
  if (communityId) params.set("c", communityId);
  const text = params.toString();
  return text ? `#${text}` : "";
}

/** Puts the view in the address bar without adding a history entry. */
export function writeHash(view) {
  const hash = hashFor(view);
  if (globalThis.location && globalThis.history?.replaceState && globalThis.location.hash !== hash) {
    globalThis.history.replaceState(null, "", hash || globalThis.location.pathname + globalThis.location.search);
  }
}
