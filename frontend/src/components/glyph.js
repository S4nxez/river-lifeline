// A status as shape and colour together, never colour alone: a circle (connected), a triangle (at risk), a square
// (isolated) or a hollow ring (no data). Drawn on a 12-unit grid at any size; the colour comes from the theme's CSS
// (.glyph-<status> in components.css), so it follows light and dark.
import { STATUSES } from "../status.js";

const SHAPES = {
  circle: '<circle cx="6" cy="6" r="5"/>',
  triangle: '<polygon points="6,1 11.2,10.6 0.8,10.6"/>',
  square: '<rect x="1.2" y="1.2" width="9.6" height="9.6"/>',
  ring: '<circle cx="6" cy="6" r="4.6"/>',
};

/** The glyph of `status` as an inline SVG of `size` px; `className` adds classes (e.g. "glyph-outlined" on the map). */
export function glyph(status, size = 11, className = "") {
  const known = STATUSES[status] ? status : "no_data";
  const shape = STATUSES[known].shape;
  const classes = ["glyph", `glyph-${known}`, `glyph-${shape}`, className].filter(Boolean).join(" ");
  return `<svg class="${classes}" viewBox="0 0 12 12" width="${size}" height="${size}" aria-hidden="true" focusable="false">${SHAPES[shape]}</svg>`;
}
