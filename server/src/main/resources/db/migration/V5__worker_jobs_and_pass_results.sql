-- =====================================================================================================================
-- V5: the backend's side of the worker contract (api/worker-api.md): the job queue and its leases, the raster bodies the
-- backend stages for the worker and receives from it, the daily river level, and the worker's results.
-- The V4 tables were never read by the code and only held synthetic demo rows: they go. Communities come from the area
-- files (server/src/main/resources/areas/), so results keep community ids without a foreign key.
-- =====================================================================================================================

DROP TABLE IF EXISTS connectivity_assessment;
DROP TABLE IF EXISTS radar_pass;
DROP TABLE IF EXISTS community;

-- ---------------------------------------------------------------------------------------------------------------------
-- Job queue
-- ---------------------------------------------------------------------------------------------------------------------

-- One unit of work for the worker. inputs is the contract's Job.inputs, written once when the job is queued.
-- lease_id is the current hand-out: the Job.id the worker sees. At most one pending job per type and subject.
CREATE TABLE job (
    id               VARCHAR(40)  PRIMARY KEY,
    type             VARCHAR(30)  NOT NULL,
    contract_version INT          NOT NULL,
    area_id          VARCHAR(50)  NULL,
    subject          VARCHAR(200) NOT NULL,
    state            VARCHAR(10)  NOT NULL,
    inputs           LONGTEXT     NOT NULL,
    attempts         INT          NOT NULL DEFAULT 0,
    max_attempts     INT          NOT NULL,
    lease_id         VARCHAR(80)  NULL,
    lease_expires_at DATETIME(3)  NULL,
    last_error       TEXT         NULL,
    created_at       DATETIME(3)  NOT NULL,
    updated_at       DATETIME(3)  NOT NULL,
    finished_at      DATETIME(3)  NULL,
    pending_key      VARCHAR(240) GENERATED ALWAYS AS (IF(state = 'pending', CONCAT(type, '|', subject), NULL)) STORED,
    CONSTRAINT uq_job_pending UNIQUE (pending_key),
    CONSTRAINT uq_job_lease UNIQUE (lease_id),
    CONSTRAINT chk_job_state CHECK (state IN ('pending', 'leased', 'done', 'failed')),
    CONSTRAINT chk_job_attempts CHECK (attempts >= 0 AND max_attempts >= 1)
);

CREATE INDEX idx_job_queue ON job (state, created_at);
CREATE INDEX idx_job_subject ON job (type, subject, state);

-- Every hand-out of a job, kept for the record: when it was leased and how it ended
CREATE TABLE job_lease (
    id         VARCHAR(80) PRIMARY KEY,
    job_id     VARCHAR(40) NOT NULL,
    leased_at  DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    ended_at   DATETIME(3) NULL,
    outcome    VARCHAR(10) NULL,
    CONSTRAINT fk_job_lease_job FOREIGN KEY (job_id) REFERENCES job (id) ON DELETE CASCADE,
    CONSTRAINT chk_job_lease_outcome CHECK (outcome IS NULL OR outcome IN ('done', 'failed', 'retried', 'expired'))
);

CREATE INDEX idx_job_lease_job ON job_lease (job_id);

-- ---------------------------------------------------------------------------------------------------------------------
-- Rasters: the body is a gzip file on the rasters volume (RIVER_RASTER_DIR), this is its description.
-- The layout columns are NULL for an upload whose result has not arrived yet.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE raster (
    id           VARCHAR(128) PRIMARY KEY,
    role         VARCHAR(12)  NOT NULL,
    width        INT          NULL,
    height       INT          NULL,
    dtype        VARCHAR(8)   NULL,
    nodata       VARCHAR(32)  NULL,
    unit         VARCHAR(16)  NULL,
    crs          VARCHAR(20)  NULL,
    origin_x     DOUBLE       NULL,
    origin_y     DOUBLE       NULL,
    step_x       DOUBLE       NULL,
    step_y       DOUBLE       NULL,
    byte_length  BIGINT       NOT NULL,
    stored_bytes BIGINT       NOT NULL,
    sha256       CHAR(64)     NOT NULL,
    lease_id     VARCHAR(80)  NULL,
    result_name  VARCHAR(12)  NULL,
    created_at   DATETIME(3)  NOT NULL,
    CONSTRAINT chk_raster_role CHECK (role IN ('input', 'occurrence', 'pending', 'result'))
);

CREATE INDEX idx_raster_pending ON raster (lease_id, result_name);
CREATE INDEX idx_raster_role ON raster (role, created_at);

-- Which rasters a job references: only those can be downloaded, and only while the job is leased
CREATE TABLE job_raster (
    job_id    VARCHAR(40)  NOT NULL,
    raster_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (job_id, raster_id),
    CONSTRAINT fk_job_raster_job FOREIGN KEY (job_id) REFERENCES job (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_raster_raster FOREIGN KEY (raster_id) REFERENCES raster (id) ON DELETE CASCADE
);

CREATE INDEX idx_job_raster_raster ON job_raster (raster_id);

-- ---------------------------------------------------------------------------------------------------------------------
-- River level: daily median at each ANA gauge station, refreshed by the backend
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE gauge_day (
    station  VARCHAR(20)  NOT NULL,
    day      DATE         NOT NULL,
    level_cm DECIMAL(8,1) NOT NULL,
    PRIMARY KEY (station, day)
);

-- ---------------------------------------------------------------------------------------------------------------------
-- Passes: found by the ASF search, then judged by the worker (assess_pass, reevaluate_pass)
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE radar_pass (
    area_id                 VARCHAR(50)  NOT NULL,
    pass_id                 VARCHAR(80)  NOT NULL,
    acquired_on             DATE         NOT NULL,
    time_utc                CHAR(5)      NOT NULL,
    track                   INT          NOT NULL,
    direction               VARCHAR(10)  NOT NULL,
    -- The granule chosen for each frame (JSON list), replaced by better ones until the pass is assessed
    granules                LONGTEXT     NOT NULL,
    discovered_at           DATETIME(3)  NOT NULL,
    -- Reading the inputs of its assess_pass job: failures, the last error, and when to try again (NULL: at once,
    -- unless staging_given_up)
    staging_attempts        INT          NOT NULL DEFAULT 0,
    staging_error           TEXT         NULL,
    staging_after           DATETIME(3)  NULL,
    staging_given_up        BOOLEAN      NOT NULL DEFAULT FALSE,
    -- The assessment (PassAssessment), NULL until the first result; method_digest is the version of the area files it
    -- was judged with
    assessed_at             DATETIME(3)  NULL,
    lease_id                VARCHAR(80)  NULL,
    method_digest           CHAR(64)     NULL,
    coverage                DOUBLE       NULL,
    shown                   BOOLEAN      NULL,
    evaluated               BOOLEAN      NULL,
    water_km2               DOUBLE       NULL,
    narrow_km2              DOUBLE       NULL,
    vegetated_km2           DOUBLE       NULL,
    threshold_source        VARCHAR(10)  NULL,
    threshold_strong_db     DOUBLE       NULL,
    threshold_weak_db       DOUBLE       NULL,
    threshold_measured_db   DOUBLE       NULL,
    threshold_tiles         INT          NULL,
    river_level_cm          DOUBLE       NULL,
    low_water               BOOLEAN      NULL,
    hub_status              VARCHAR(10)  NULL,
    hub_bottleneck_m        INT          NULL,
    hub_distance_to_water_m INT          NULL,
    stats_seconds           DOUBLE       NULL,
    stats_spacing_m         DOUBLE       NULL,
    water_raster_id         VARCHAR(128) NULL,
    radar_raster_id         VARCHAR(128) NULL,
    sentinel1_raster_id     VARCHAR(128) NULL,
    PRIMARY KEY (area_id, pass_id),
    CONSTRAINT chk_radar_pass_direction CHECK (direction IN ('ascending', 'descending')),
    CONSTRAINT chk_radar_pass_hub CHECK (hub_status IS NULL OR hub_status IN ('connected', 'at_risk', 'isolated', 'no_data'))
);

CREATE INDEX idx_radar_pass_track ON radar_pass (area_id, track, acquired_on);
CREATE INDEX idx_radar_pass_staging ON radar_pass (assessed_at, staging_given_up, acquired_on);

-- How the pass's water agrees with Sentinel-1, when the pass used scenes
CREATE TABLE pass_sentinel1 (
    area_id            VARCHAR(50) NOT NULL,
    pass_id            VARCHAR(80) NOT NULL,
    dates              TEXT        NOT NULL,
    judged_km2         DOUBLE      NOT NULL,
    agreement          DOUBLE      NULL,
    both_water_km2     DOUBLE      NOT NULL,
    nisar_only_km2     DOUBLE      NOT NULL,
    sentinel1_only_km2 DOUBLE      NOT NULL,
    mud_km2            DOUBLE      NOT NULL,
    PRIMARY KEY (area_id, pass_id),
    CONSTRAINT fk_pass_sentinel1_pass FOREIGN KEY (area_id, pass_id) REFERENCES radar_pass (area_id, pass_id) ON DELETE CASCADE
);

-- What a pass says about one community's route to the hub
CREATE TABLE connectivity_assessment (
    area_id             VARCHAR(50) NOT NULL,
    pass_id             VARCHAR(80) NOT NULL,
    community_id        VARCHAR(80) NOT NULL,
    status              VARCHAR(10) NOT NULL,
    bottleneck_m        INT         NULL,
    distance_to_water_m INT         NULL,
    -- Held at risk by unverified low-water ground
    unverified_route    BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (area_id, pass_id, community_id),
    CONSTRAINT fk_assessment_pass FOREIGN KEY (area_id, pass_id) REFERENCES radar_pass (area_id, pass_id) ON DELETE CASCADE,
    CONSTRAINT chk_assessment_status CHECK (status IN ('connected', 'at_risk', 'isolated', 'no_data'))
);

-- Water lost and gained on a pass since the previous shown pass of its track
CREATE TABLE pass_change (
    area_id          VARCHAR(50)  NOT NULL,
    pass_id          VARCHAR(80)  NOT NULL,
    since_pass_id    VARCHAR(80)  NOT NULL,
    lost_km2         DOUBLE       NOT NULL,
    gained_km2       DOUBLE       NOT NULL,
    change_raster_id VARCHAR(128) NOT NULL,
    created_at       DATETIME(3)  NOT NULL,
    PRIMARY KEY (area_id, pass_id, since_pass_id),
    CONSTRAINT fk_pass_change_pass FOREIGN KEY (area_id, pass_id) REFERENCES radar_pass (area_id, pass_id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------------------------------------------------
-- Whole record (classify_communities): replaced as a whole by every classification
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE community_class (
    area_id      VARCHAR(50) NOT NULL,
    community_id VARCHAR(80) NOT NULL,
    class        VARCHAR(12) NOT NULL,
    updated_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (area_id, community_id),
    CONSTRAINT chk_community_class CHECK (class IN ('monitored', 'unresolved', 'inland', 'no_data'))
);

-- Counts over monitored communities on each evaluated pass; an unconfirmed isolation counts as at risk
CREATE TABLE pass_counts (
    area_id     VARCHAR(50) NOT NULL,
    pass_id     VARCHAR(80) NOT NULL,
    connected   INT         NOT NULL,
    at_risk     INT         NOT NULL,
    isolated    INT         NOT NULL,
    no_data     INT         NOT NULL,
    -- Community ids isolated on this pass but not on the previous one with data (JSON list)
    unconfirmed TEXT        NOT NULL,
    updated_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (area_id, pass_id)
);

-- ---------------------------------------------------------------------------------------------------------------------
-- Droughts: models the worker trained (plain data, never executed) and its forecasts
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE drought_model (
    region        VARCHAR(50) NOT NULL,
    version       INT         NOT NULL,
    kind          VARCHAR(40) NOT NULL,
    lookback_days INT         NOT NULL,
    parameters    LONGTEXT    NOT NULL,
    observations  INT         NOT NULL,
    droughts      INT         NOT NULL,
    trained_at    DATETIME(3) NOT NULL,
    PRIMARY KEY (region, version)
);

CREATE TABLE drought_forecast (
    region       VARCHAR(50) NOT NULL,
    as_of        DATE        NOT NULL,
    horizon_days INT         NOT NULL,
    probability  DOUBLE      NOT NULL,
    risk_level   VARCHAR(10) NOT NULL,
    created_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (region, as_of, horizon_days),
    CONSTRAINT chk_forecast_risk CHECK (risk_level IN ('low', 'medium', 'high')),
    CONSTRAINT chk_forecast_probability CHECK (probability BETWEEN 0 AND 1)
);
