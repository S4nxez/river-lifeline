#!/usr/bin/env bash
# Removes this app's backend and frontend images except the deployed tag: every release pulls images under a new
# commit SHA tag, and without this the old ones pile up on the Pi. Other stacks' images are never touched.
# Images a container still uses are skipped (podman refuses to remove them); a rollback pulls its tag from GHCR again.
#
# Usage: scripts/prune-images.sh IMAGE_PREFIX KEEP_TAG
#   e.g. scripts/prune-images.sh ghcr.io/s4nxez/hackathon-nisar-sar-change-detection <sha>
# Run by Release on the Pi through `ssh pi bash -s`, so no command may read stdin.
set -uo pipefail

prefix="${1:?usage: prune-images.sh IMAGE_PREFIX KEEP_TAG}"
keep="${2:?usage: prune-images.sh IMAGE_PREFIX KEEP_TAG}"

podman images --format '{{.ID}} {{.Repository}} {{.Tag}}' | while read -r id repo tag; do
  [[ "$repo" == "$prefix-back" || "$repo" == "$prefix-front" ]] || continue
  [[ "$tag" != "$keep" ]] || continue
  if [[ "$tag" == "<none>" ]]; then ref="$id"; else ref="$repo:$tag"; fi
  if podman rmi "$ref" </dev/null >/dev/null 2>&1; then
    echo "removed $ref"
  else
    echo "kept $ref (in use)"
  fi
done
