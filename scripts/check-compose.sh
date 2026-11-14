#!/usr/bin/env bash
# Fails when podman-compose (the one that deploys on the Pi) leaves a ${...} unexpanded in the production compose
# file. CI starts the stack with docker compose, which expands them all, so this is the only check of what the Pi gets.
# Known case: podman-compose 1.3 keeps `NAME: ${NAME:-}` literally when NAME is unset, and the container receives
# "${NAME:-}" as its value.
#
# Usage: scripts/check-compose.sh   (needs podman-compose; CI installs the Pi's version)
set -euo pipefail
cd "$(dirname "$0")/.."

# Only the required variables, as in a fresh .env: every optional one must resolve on its own
config="$(env -i PATH="$PATH" HOME="${HOME:-/tmp}" MYSQL_ROOT_PASSWORD=check \
  podman-compose --env-file /dev/null -f podman-compose.yml config)"

if unresolved="$(grep -n '\${' <<<"$config")"; then
  echo "::error file=podman-compose.yml::podman-compose leaves variables unexpanded:"
  sed 's/^/  /' <<<"$unresolved"
  exit 1
fi
echo "OK: podman-compose expands every variable of podman-compose.yml"
