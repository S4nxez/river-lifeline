// Light or dark page. The choice lives in <html data-theme>; index.html sets it before the first paint (the saved
// choice, else the system's) with the same storage key, so the page never flashes the wrong theme.

export const STORAGE_KEY = "river-lifeline-theme";

/** The theme on screen: "dark" or "light". */
export function currentTheme() {
  return document.documentElement.dataset.theme === "dark" ? "dark" : "light";
}

/** Shows the page in `theme` and remembers it for the next visit (when the browser lets us store it). */
export function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // Private mode or blocked storage: the theme still applies, it just isn't remembered
  }
}

/** Switches to the other theme and returns it. */
export function toggleTheme() {
  const next = currentTheme() === "dark" ? "light" : "dark";
  applyTheme(next);
  return next;
}
