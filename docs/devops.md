# DevOps

**Flow:** short-lived branch → PR to `main` → green **CI** → merge → **Release** deploys to the Raspberry Pi on its own.

| Workflow | When | What it does |
|---|---|---|
| `ci.yml` | Every PR (and the first stage of Release) | On PRs, `scripts/check-tests-changed.sh` (code changes need test changes, see `AGENTS.md`). Frontend `node --check`, Prettier check and unit tests, worker Ruff + pytest, `mvn verify` (unit tests + Spotless check), starts the full stack with `compose.build.yml` and runs `scripts/smoke.sh` |
| `release.yml` | Push to `main` | CI → `:latest` and `:<sha>` images on GHCR (native ARM runner) → over Tailscale + SSH: `git pull`, `podman pull`, `podman-compose up` → smoke tests on the Pi and on the public URL. On failure it prints the backend logs; on success it removes this app's older images from the Pi (`scripts/prune-images.sh`) |

**Smoke test** (`scripts/smoke.sh URL [seconds]`): `/api/communities` returns the communities (Spring, Flyway and MySQL are up) and `/` serves the page. Works locally too.

**Rollback** (no rebuild): `gh workflow run release.yml -f tag=<full SHA of a good commit>`. The next push to `main` deploys the latest code again.

**Local**
- `docker compose -f compose.dev.yml up -d`: MySQL (root/root, same as `application.properties`) and the frontend on http://localhost:5500, served from disk (reload to see edits) with `/api` proxied to the backend on the host's port 8080. Run the backend from the IDE or with `mvn spring-boot:run`.
- Full stack as in production: `mvn -f server/pom.xml -DskipTests package`, then `docker compose -f podman-compose.yml -f compose.build.yml up -d --build` → http://127.0.0.1:8091

**Formatting** (120 columns, LF line endings via `.gitattributes`)
- Frontend: Prettier, version pinned in `frontend/package.json`. Server: Spotless with palantir-java-format (`server/pom.xml`). Worker: Ruff (`worker/pyproject.toml`), run from its virtualenv (`worker/README.md`).
- Once per clone: `npm --prefix frontend install`. It installs Prettier and enables `.githooks/pre-commit`, which formats the staged files on every commit.
- By hand: `npm --prefix frontend run format` and `mvn -f server/pom.xml spotless:apply`. CI rejects unformatted code.

**Team rules**
- Flyway migrations: claim a version number before creating one (`V4__` A, `V5__` B…). Never edit a merged migration: Flyway refuses to start on a checksum mismatch.
- The frontend uses relative URLs (`./api/...`): the app is served under `/river-lifeline/`.
- Do not develop in `/opt/HACKATHON-NISAR-SAR-Change-Detection` on the Pi: it is the production checkout and the deploy runs `git pull --ff-only` there.

**Configuration:** secrets `CLIENT_ID`/`CLIENT_SECRET` (Tailscale OAuth client, tag `tag:ci`), `SSH_PRIVATE_KEY`, `DEPLOY_HOST` (the Pi's Tailscale IP) and `DEPLOY_USER`. On the Pi: a `.env` with `MYSQL_ROOT_PASSWORD` next to `podman-compose.yml`, and Funnel `/river-lifeline` → `127.0.0.1:8091`. Recommended on GitHub: protect `main` against force pushes and deletion, and require a PR with the `checks` status check.
