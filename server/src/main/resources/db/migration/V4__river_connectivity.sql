-- =====================================================================================================================
-- V4: River connectivity of the riverside communities around Tefé (Amazonas, Brazil).
-- In droughts the rivers drop and communities lose the water route to Tefé, their hub for food, fuel, health care and
-- school. Every NISAR pass tells, community by community, whether that route still exists and how narrow it is;
-- a Sentinel-1 scene close in time, when there is one, only cross-checks the NISAR result.
-- Replaces the generic change-detection demo of V1 and V3.
-- =====================================================================================================================

DROP TABLE IF EXISTS sar_observation;
DROP TABLE IF EXISTS sar_site;

CREATE TABLE community (
    id        VARCHAR(50)  PRIMARY KEY,
    name      VARCHAR(150) NOT NULL,
    kind      VARCHAR(20)  NOT NULL,
    latitude  DECIMAL(9,6) NOT NULL,
    longitude DECIMAL(9,6) NOT NULL,
    CONSTRAINT chk_community_kind CHECK (kind IN ('HUB', 'TOWN', 'VILLAGE')),
    CONSTRAINT chk_community_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT chk_community_longitude CHECK (longitude BETWEEN -180 AND 180)
);

-- One radar acquisition over the area
CREATE TABLE radar_pass (
    id             VARCHAR(80) PRIMARY KEY,
    mission        VARCHAR(20) NOT NULL DEFAULT 'NISAR',
    acquired_on    DATE        NOT NULL,
    -- River gauge at Tefé on the day of the pass, when known
    river_level_cm INT         NULL,
    CONSTRAINT chk_radar_pass_mission CHECK (mission IN ('NISAR'))
);

-- What a pass says about one community
CREATE TABLE connectivity_assessment (
    pass_id            VARCHAR(80) NOT NULL,
    community_id       VARCHAR(50) NOT NULL,
    status             VARCHAR(20) NOT NULL,
    -- Narrowest point of the water route to the hub; NULL when there is no route or no data
    route_width_m      INT         NULL,
    -- Sentinel-1 scene used to cross-check this assessment, and whether it confirms it; NULL when there was none
    sentinel1_scene_on DATE        NULL,
    sentinel1_agrees   BOOLEAN     NULL,
    PRIMARY KEY (pass_id, community_id),
    CONSTRAINT fk_assessment_pass FOREIGN KEY (pass_id) REFERENCES radar_pass (id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_community FOREIGN KEY (community_id) REFERENCES community (id) ON DELETE CASCADE,
    CONSTRAINT chk_assessment_status CHECK (status IN ('CONNECTED', 'AT_RISK', 'ISOLATED', 'NO_DATA')),
    CONSTRAINT chk_assessment_width CHECK (route_width_m IS NULL OR route_width_m > 0)
);

CREATE INDEX idx_radar_pass_acquired_on ON radar_pass (acquired_on);

-- ---------------------------------------------------------------------------------------------------------------------
-- Demo data until the worker produces real assessments: approximate positions of real places, synthetic results
-- following a drought (four NISAR passes, 12 days apart, with the river falling).
-- ---------------------------------------------------------------------------------------------------------------------

INSERT INTO community (id, name, kind, latitude, longitude) VALUES
    ('tefe',     'Tefé',     'HUB',     -3.354000, -64.711000),
    ('alvaraes', 'Alvarães', 'TOWN',    -3.221000, -64.804000),
    ('uarini',   'Uarini',   'TOWN',    -2.990000, -65.113000),
    ('nogueira', 'Nogueira', 'VILLAGE', -3.295000, -64.860000),
    ('caiambe',  'Caiambé',  'VILLAGE', -3.545000, -64.400000);

INSERT INTO radar_pass (id, mission, acquired_on, river_level_cm) VALUES
    ('nisar-2026-08-04', 'NISAR', '2026-08-04', 1650),
    ('nisar-2026-08-16', 'NISAR', '2026-08-16', 1420),
    ('nisar-2026-08-28', 'NISAR', '2026-08-28', 1180),
    ('nisar-2026-09-09', 'NISAR', '2026-09-09', 980);

INSERT INTO connectivity_assessment
    (pass_id, community_id, status, route_width_m, sentinel1_scene_on, sentinel1_agrees) VALUES
    ('nisar-2026-08-04', 'alvaraes', 'CONNECTED', 400, NULL,         NULL),
    ('nisar-2026-08-04', 'uarini',   'CONNECTED', 200, NULL,         NULL),
    ('nisar-2026-08-04', 'nogueira', 'CONNECTED', 100, NULL,         NULL),
    ('nisar-2026-08-04', 'caiambe',  'CONNECTED', 200, NULL,         NULL),
    ('nisar-2026-08-16', 'alvaraes', 'CONNECTED', 400, '2026-08-15', TRUE),
    ('nisar-2026-08-16', 'uarini',   'CONNECTED', 200, '2026-08-15', TRUE),
    ('nisar-2026-08-16', 'nogueira', 'AT_RISK',   40,  '2026-08-15', TRUE),
    ('nisar-2026-08-16', 'caiambe',  'CONNECTED', 100, '2026-08-15', TRUE),
    ('nisar-2026-08-28', 'alvaraes', 'CONNECTED', 200, '2026-08-27', TRUE),
    ('nisar-2026-08-28', 'uarini',   'AT_RISK',   40,  '2026-08-27', FALSE),
    ('nisar-2026-08-28', 'nogueira', 'ISOLATED',  NULL, '2026-08-27', TRUE),
    ('nisar-2026-08-28', 'caiambe',  'CONNECTED', 60,  '2026-08-27', TRUE),
    ('nisar-2026-09-09', 'alvaraes', 'CONNECTED', 100, '2026-09-08', TRUE),
    ('nisar-2026-09-09', 'uarini',   'ISOLATED',  NULL, '2026-09-08', TRUE),
    ('nisar-2026-09-09', 'nogueira', 'ISOLATED',  NULL, '2026-09-08', TRUE),
    ('nisar-2026-09-09', 'caiambe',  'NO_DATA',   NULL, NULL,         NULL);
