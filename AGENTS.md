# AGENTS.md

Rules for everyone who changes this repository, people and coding agents alike.

## Language: English only

Everything in the repository and in git is written in English: code, identifiers, comments, user-facing text, SQL
data and comments, documentation, commit messages, branch names, pull request titles and descriptions, and review
comments. No Spanish or any other language, even when you are being instructed in another language.

## Every change ships with unit tests

- A change to behavior comes with unit tests in the same pull request: new code gets new tests, changed code gets
  updated tests, and a bug fix gets a test that fails without the fix.
- CI enforces it on pull requests (`scripts/check-tests-changed.sh`): changes to `server/src/main/java` require changes
  to `server/src/test/java`, changes to `frontend/src/**/*.js` require changes to `frontend/tests`, and changes to
  `worker/src` require changes to `worker/tests`.
- Backend: JUnit 5, Mockito and AssertJ in `server/src/test/java`, mirroring the main packages. Unit tests never need a
  database. Run `mvn -f server/pom.xml test`.
- Frontend: `node:test` and jsdom in `frontend/tests/*.test.js`. Browser globals (Leaflet's `L`, `Chart`, `fetch`) are
  faked. Run `npm --prefix frontend test`.
- Worker: pytest in `worker/tests`, with the in-memory fakes of `worker/tests/fakes.py` for the ports. Run `pytest` in
  `worker/` (setup in `worker/README.md`).
- All tests pass before you push.

## Formatting

Prettier formats `frontend/`, Spotless with palantir-java-format formats `server/`, Ruff formats and lints `worker/`.
The pre-commit hook applies them (enable it once per clone with `npm --prefix frontend install`). Don't reformat code unrelated to your change.

## Architecture

- Three layers. The worker (`worker/`) does all the processing and talks only to the backend, through the internal
  worker API (`api/worker-api.yaml`, explained in `api/worker-api.md`). The backend (`server/`) is the intermediary: it
  alone calls the external services (ASF, Earthdata, Planetary Computer, ANA, Open-Meteo), keeps the area files, the
  database and the raster store, hands jobs with all their inputs to the worker and stores its results; it never
  computes science (it only paints the layer images from the result rasters). The UI talks only to the backend, under
  `/api/v1` (GET only), which serves the worker's results (`RIVER_SOURCE=pipeline`, production) or the files of
  `RIVER_DATA_DIR` (`RIVER_SOURCE=files`, the default, for local runs on the fixtures).
- Nothing is specific to one area: each study area is a file `server/src/main/resources/areas/<id>.json` (with its
  communities and occurrence next to it) and, for `/api/v1`, a folder `aois/<id>/`. Never hardcode an area, a width or
  a coverage threshold in the backend or the UI.
- The server follows hexagonal architecture with DDD in two bounded contexts, `monitoring` (`/api/v1`) and `pipeline`
  (the worker API and the external sources): `domain` (model, rules) ← `application` (use cases and ports) ←
  `infrastructure` (web, database, file system and HTTP adapters, Spring wiring). Domain and application don't use
  Spring or Jackson, and neither context uses the other. `server/src/test/java/com/riverlifeline/ArchitectureTest.java`
  fails the build on a violation.
- The worker follows clean architecture: `domain` ← `application` ← `adapters` ← `entrypoints`, each layer importing
  only the ones inside it. `worker/tests/test_architecture.py` fails the build on a violation.

## Workflow

- Short-lived branch, pull request to `main`, green CI, merge. Release then deploys `main` to production on its own.
- Conventional commits: `feat(scope): ...`, `fix`, `test`, `chore`, `docs`, `style`.
- Flyway: never edit a migration that has been merged; add a new `V<n>__` file, and agree on the number with the team.
- Data contract: the files processing writes and the `/api/v1` JSON follow CONTRACTS.md. Changing them means a PR
  that updates the contract, `fixtures/data` and every layer together.
- The frontend calls the API with relative URLs (`./api/...`) because the app is served under a path prefix.
- CI/CD, local setup and rollback: `docs/devops.md`.
