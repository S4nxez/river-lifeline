# River Lifeline

Which riverside communities around Tefé (Amazonas, Brazil) can still reach the town by water?

In severe droughts the rivers drop and communities lose their only road to Tefé, where they get food, fuel, health care
and school. River Lifeline uses every pass of the NISAR radar (L-band: it sees water through clouds, smoke and the
forest canopy) to tell, community by community, whether a water route to Tefé still exists and how narrow it has
become. Sentinel-1 is only used to cross-check each result.

| Status | Meaning |
|---|---|
| Connected | A water route at least 60 m wide links the community to Tefé |
| At risk | A route remains, narrower than 60 m |
| Isolated | No water route left |
| No data | The pass did not cover the community or its route |

**Demo:** https://raspberrypi.tail0a4b52.ts.net/river-lifeline/

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot, MySQL 8, Flyway: read-only API over the processing output files, hexagonal architecture |
| Frontend | Vanilla JavaScript (ES modules), Leaflet |
| Worker | Python 3.12: water route mapping on NISAR passes, Sentinel-1 cross-check, drought forecasting |
| Infrastructure | Podman + Caddy on a Raspberry Pi, published with Tailscale Funnel |
| CI/CD | GitHub Actions, images on GHCR |

## Architecture

```mermaid
flowchart LR
    user([Browser])

    subgraph pi["Raspberry Pi · Podman"]
        caddy["Caddy<br/>reverse proxy"]
        frontend["Frontend<br/>nginx · vanilla JS"]
        backend["Backend<br/>Spring Boot · read-only"]
        db[("MySQL<br/>Flyway migrations")]
        data[("RIVER_DATA_DIR<br/>aois/&lt;id&gt;/catalog.json,<br/>statuses, layer PNGs")]
        worker["Processing<br/>Python · every 6 h"]
    end

    subgraph external["External data"]
        earthdata["ASF / NASA Earthdata<br/>NISAR"]
        s1["Sentinel-1, JRC, OSM, ANA"]
    end

    user -->|"HTTPS · Tailscale Funnel"| caddy
    caddy -->|"/"| frontend
    caddy -->|"/api/v1/*"| backend
    backend -->|"reads"| data
    backend --> db
    worker -.->|"writes"| data
    worker -.-> earthdata
    worker -.-> s1

    classDef planned stroke-dasharray: 5 5
    class worker,earthdata,s1 planned
```

Dashed: planned, not running yet. The layers only meet through two contracts (CONTRACTS.md): processing writes files
to `RIVER_DATA_DIR` and never serves requests; the backend reads, validates and serves them under `/api/v1` and never
computes science; the browser only talks to the backend. Every study area is a folder `aois/<id>/` with its own
coordinates and thresholds, so a new area needs no code change. Until real passes exist, the backend serves the
hand-made fixtures of `fixtures/data`.

## Run it locally

```bash
npm --prefix frontend install               # once per clone: Prettier + the pre-commit hook
docker compose -f compose.dev.yml up -d    # MySQL + frontend on http://localhost:5500 (/api -> :8080)
cd server && mvn spring-boot:run            # backend on :8080, using that MySQL, serving ../fixtures/data
```

Full stack, as in production: see `compose.build.yml`. CI/CD, deployment and rollback: [docs/devops.md](docs/devops.md).
