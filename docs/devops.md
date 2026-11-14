# DevOps

**Flow:** short-lived branch → PR to `main` → green **CI** → merge → **Release** deploys to the Raspberry Pi on its own.

| Workflow | When | What it does |
|---|---|---|
| `ci.yml` | Every PR (and the first stage of Release) | On PRs, `scripts/check-tests-changed.sh` (code changes need test changes, see `AGENTS.md`). Frontend `node --check`, Prettier check and unit tests, worker Ruff + pytest, `mvn verify` (unit tests + Spotless check), starts the full stack with `compose.build.yml` and runs `scripts/smoke.sh` |
| `release.yml` | Push to `main` | CI → `:latest` and `:<sha>` images on GHCR (native ARM runner) → over Tailscale + SSH: `git pull`, `podman pull`, `podman-compose up` → smoke tests on the Pi and on the public URL. On failure it prints the backend logs; on success it removes this app's older images from the Pi (`scripts/prune-images.sh`) |

**Smoke test** (`scripts/smoke.sh URL [seconds]`): `/api/v1/health` says `"status":"ok"` (Spring, Flyway and MySQL are up, and a catalog was loaded from the data folder) and `/` serves the page. Works locally too.

**Rollback** (no rebuild): `gh workflow run release.yml -f tag=<full SHA of a good commit>`. The next push to `main` deploys the latest code again.

**Local**
- `docker compose -f compose.dev.yml up -d`: MySQL (root/root, same as `application.properties`) and the frontend on http://localhost:5500, served from disk (reload to see edits) with `/api` proxied to the backend on the host's port 8080. Run the backend from the IDE or with `mvn spring-boot:run` in `server/`: it serves the API from `../fixtures/data` (env `RIVER_DATA_DIR`, `PORT`, `DEFAULT_AOI`, `CORS_ORIGINS` override it).
- Full stack as in production: `mvn -f server/pom.xml -DskipTests package`, then `docker compose -f podman-compose.yml -f compose.build.yml up -d --build` → http://127.0.0.1:8091
- Everything at once on Windows: `scripts/local-stack.ps1`, or the IntelliJ run configuration "Local stack" (`.run/`). It builds the jar, starts the full stack plus the `front` of `compose.dev.yml` on http://localhost:5500 (the frontend from disk, so a reload shows every edit, with `/api` proxied to :8091; open to other devices such as a phone on the tailnet), opens the browser and follows the backend logs. Stopping it runs `docker compose down`.

**Formatting** (120 columns, LF line endings via `.gitattributes`)
- Frontend: Prettier, version pinned in `frontend/package.json`. Server: Spotless with palantir-java-format (`server/pom.xml`). Worker: Ruff (`worker/pyproject.toml`), run from its virtualenv (`worker/README.md`).
- Once per clone: `npm --prefix frontend install`. It installs Prettier and enables `.githooks/pre-commit`, which formats the staged files on every commit.
- By hand: `npm --prefix frontend run format` and `mvn -f server/pom.xml spotless:apply`. CI rejects unformatted code.

**Team rules**
- Flyway migrations: claim a version number before creating one (`V4__` A, `V5__` B…). Never edit a merged migration: Flyway refuses to start on a checksum mismatch.
- Data: the backend reads the processing output read-only. A new study area is a new folder `aois/<id>/` in the data folder; no code or configuration change. A change to the files' or the API's shape follows CONTRACTS.md (contract, fixtures and all layers in one PR).
- The frontend uses relative URLs (`./api/...`): the app is served under `/river-lifeline/`.
- Do not develop in `/opt/HACKATHON-NISAR-SAR-Change-Detection` on the Pi: it is the production checkout and the deploy runs `git pull --ff-only` there.

**Configuration:** secrets `CLIENT_ID`/`CLIENT_SECRET` (Tailscale OAuth client, tag `tag:ci`), `SSH_PRIVATE_KEY`, `DEPLOY_HOST` (the Pi's Tailscale IP) and `DEPLOY_USER`. On the Pi: a `.env` with `MYSQL_ROOT_PASSWORD` next to `podman-compose.yml` (optionally also `RIVER_DATA_HOST_DIR`, the folder processing writes to, default `./fixtures/data`, and `DEFAULT_AOI`), and Funnel `/river-lifeline` → `127.0.0.1:8091`. Recommended on GitHub: protect `main` against force pushes and deletion, and require a PR with the `checks` status check.
