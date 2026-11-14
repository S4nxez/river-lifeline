#!/usr/bin/env bash
# Waits until the stack behind Caddy works end to end: the API serves the site catalog
# from MySQL (Spring + Flyway + DB are up) and the frontend page loads.
#
# Usage: scripts/smoke.sh BASE_URL [TIMEOUT_SECONDS]   (default timeout: 120)
# Exits 0 when both checks pass, 1 on timeout. Does not read stdin, so it can run as `ssh host bash -s < smoke.sh`.
set -uo pipefail

base="${1:?usage: smoke.sh BASE_URL [TIMEOUT_SECONDS]}"
base="${base%/}"
deadline=$((SECONDS + ${2:-120}))

# expect URL SUBSTRING: the URL answers 2xx and its body contains SUBSTRING
expect() {
  local body
  body="$(curl -fsS --max-time 10 "$1" 2>&1)" || { last="$1: $body"; return 1; }
  [[ "$body" == *"$2"* ]] || { last="$1: answered without '$2'"; return 1; }
}

last=""
until expect "$base/api/sites" '"sites"' && expect "$base/" '<html'; do
  if ((SECONDS >= deadline)); then
    echo "FAIL $last"
    exit 1
  fi
  sleep 5
done
echo "OK $base (api + frontend)"
