import { test } from "node:test";
import assert from "node:assert/strict";
import { installDom } from "./helpers/browser.js";
import { STORAGE_KEY, applyTheme, currentTheme, toggleTheme } from "../src/theme.js";

const document = installDom();
const stored = new Map();
globalThis.localStorage = { setItem: (key, value) => stored.set(key, value) };

test("the page is light unless <html> says dark", () => {
  delete document.documentElement.dataset.theme;
  assert.equal(currentTheme(), "light");

  document.documentElement.dataset.theme = "dark";
  assert.equal(currentTheme(), "dark");
});

test("applyTheme shows the theme and remembers it for the next visit", () => {
  applyTheme("dark");

  assert.equal(document.documentElement.dataset.theme, "dark");
  assert.equal(stored.get(STORAGE_KEY), "dark");
});

test("toggleTheme switches to the other theme and returns it", () => {
  applyTheme("light");

  assert.equal(toggleTheme(), "dark");
  assert.equal(currentTheme(), "dark");
  assert.equal(toggleTheme(), "light");
  assert.equal(stored.get(STORAGE_KEY), "light");
});

test("the theme still applies when the browser blocks storage", () => {
  const storage = globalThis.localStorage;
  globalThis.localStorage = {
    setItem: () => {
      throw new Error("blocked");
    },
  };
  try {
    applyTheme("dark");
    assert.equal(currentTheme(), "dark");
  } finally {
    globalThis.localStorage = storage;
  }
});
