# DevOps

**Flow:** short-lived branch → PR to `main` → green **CI** → merge → **Release** deploys to the Raspberry Pi on its own.

| Workflow | When | What it does |
|---|---|---|
| `ci.yml` | Every PR (and the first stage of Release) | Frontend `node --check`, `mvn verify`, starts the full stack with `compose.build.yml` and runs `scripts/smoke.sh` |
| `release.yml` | Push to `main` | CI → `:latest` and `:<sha>` images on GHCR (native ARM runner) → over Tailscale + SSH: `git pull`, `podman pull`, `podman-compose up` → smoke tests on the Pi and on the public URL. On failure it prints the backend logs |

**Smoke test** (`scripts/smoke.sh URL [seconds]`): `/api/sites` returns the catalog (Spring, Flyway and MySQL are up) and `/` serves the page. Works locally too.

**Rollback** (no rebuild): `gh workflow run release.yml -f tag=<full SHA of a good commit>`. The next push to `main` deploys the latest code again.

**Local**
- Database only, for the IDE: `docker compose -f compose.dev.yml up -d` (root/root, same as `application.properties`).
- Full stack as in production: `mvn -f server/pom.xml -DskipTests package`, then `docker compose -f podman-compose.yml -f compose.build.yml up -d --build` → http://127.0.0.1:8091

**Team rules**
- Flyway migrations: claim a version number before creating one (`V4__` A, `V5__` B…). Never edit a merged migration: Flyway refuses to start on a checksum mismatch.
- The frontend uses relative URLs (`./api/...`): the app is served under `/river-lifeline/`.
- Do not develop in `/opt/HACKATHON-NISAR-SAR-Change-Detection` on the Pi: it is the production checkout and the deploy runs `git pull --ff-only` there.

**Configuration:** secrets `CLIENT_ID`/`CLIENT_SECRET` (Tailscale OAuth client, tag `tag:ci`), `SSH_PRIVATE_KEY`, `DEPLOY_HOST` (the Pi's Tailscale IP) and `DEPLOY_USER`. On the Pi: a `.env` with `MYSQL_ROOT_PASSWORD` next to `podman-compose.yml`, and Funnel `/river-lifeline` → `127.0.0.1:8091`. Recommended on GitHub: protect `main` against force pushes and deletion, and require a PR with the `checks` status check.
