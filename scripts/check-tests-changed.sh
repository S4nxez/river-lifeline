#!/usr/bin/env bash
# Fails when a pull request changes production code without changing its unit tests (AGENTS.md: every change ships
# with unit tests). Deleted source files don't count: removing code needs no new tests.
#
# Usage: scripts/check-tests-changed.sh BASE_REF   (e.g. origin/main, which must be fetched)
set -euo pipefail

base="${1:?usage: check-tests-changed.sh BASE_REF}"
sources="$(git diff --name-only --diff-filter=ACMR "$base"...HEAD)"
all="$(git diff --name-only "$base"...HEAD)"

failed=0
# require AREA SOURCE_PATTERN TEST_PATTERN
require() {
  local changed
  changed="$(grep -E "$2" <<<"$sources" || true)"
  if [[ -n "$changed" ]] && ! grep -qE "$3" <<<"$all"; then
    echo "::error title=Missing unit tests::$1 code changed but nothing under ${3#^} did. See AGENTS.md"
    sed 's/^/  /' <<<"$changed"
    failed=1
  fi
}

require Backend '^server/src/main/java/' '^server/src/test/java/'
require Frontend '^frontend/src/.*\.js$' '^frontend/tests/'

if [[ "$failed" -eq 0 ]]; then echo "OK: code changes come with test changes"; fi
exit "$failed"
