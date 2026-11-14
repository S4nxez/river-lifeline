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
  to `server/src/test/java`, and changes to `frontend/src/**/*.js` require changes to `frontend/tests`.
- Backend: JUnit 5, Mockito and AssertJ in `server/src/test/java`, mirroring the main packages. Unit tests never need a
  database. Run `mvn -f server/pom.xml test`.
- Frontend: `node:test` and jsdom in `frontend/tests/*.test.js`. Browser globals (Leaflet's `L`, `Chart`, `fetch`) are
  faked. Run `npm --prefix frontend test`.
- All tests pass before you push.

## Formatting

Prettier formats `frontend/`, Spotless with palantir-java-format formats `server/`. The pre-commit hook applies both
(enable it once per clone with `npm --prefix frontend install`). Don't reformat code unrelated to your change.

## Workflow

- Short-lived branch, pull request to `main`, green CI, merge. Release then deploys `main` to production on its own.
- Conventional commits: `feat(scope): ...`, `fix`, `test`, `chore`, `docs`, `style`.
- Flyway: never edit a migration that has been merged; add a new `V<n>__` file, and agree on the number with the team.
- The frontend calls the API with relative URLs (`./api/...`) because the app is served under a path prefix.
- CI/CD, local setup and rollback: `docs/devops.md`.
