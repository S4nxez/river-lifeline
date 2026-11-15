# Early warning files

Default mount of the backend's `/early-warning` (`podman-compose.yml`): empty, so `/api/v1/aois/{aoi}/early-warning`
answers 404. In production, `RIVER_EARLY_WARNING_HOST_DIR` in the `.env` points at a folder outside the checkout that
holds `aois/<id>/early_warning.json`, written by the worker's CLI (`docs/early-warning.md`).
