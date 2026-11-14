
USE NisarSar;

-- =========================================================
-- V3: Demo data for NISAR SAR Change Detection
-- Real geographic locations; synthetic measurements.
-- =========================================================

INSERT INTO sar_site (
    id, name, country, latitude, longitude,
    change_level, change_value, category,
    description, confidence
) VALUES
      (
          'ES_VALENCIA_001',
          'Albufera lagoon, Valencia',
          'Spain',
          39.3040, -0.3550,
          'HIGH', 'INCREASE',
          'FLOOD',
          'Wetland near Valencia to simulate changes in flooded surface.',
          0.965
      ),
      (
          'ES_DONANA_001',
          'Donana National Park',
          'Spain',
          37.0000, -6.4500,
          'MEDIUM', 'DECREASE',
          'WETLAND',
          'Donana wetlands to monitor changes in moisture and flooded surface.',
          0.913
      ),
      (
          'ES_CUENCA_001',
          'Cuenca mountain range',
          'Spain',
          40.2500, -1.9500,
          'HIGH', 'DECREASE',
          'WILDFIRE',
          'Forest area to simulate backscatter changes after a wildfire.',
          0.942
      ),
      (
          'BR_AMAZON_001',
          'Amazon - Manaus area',
          'Brazil',
          -3.1000, -60.0000,
          'HIGH', 'DECREASE',
          'DEFORESTATION',
          'Tropical forest area to simulate forest cover loss.',
          0.957
      ),
      (
          'BR_PANTANAL_001',
          'Pantanal - Mato Grosso do Sul',
          'Brazil',
          -19.0000, -57.0000,
          'MEDIUM', 'INCREASE',
          'FLOOD',
          'Pantanal wetlands to simulate seasonal flooding changes.',
          0.901
      ),
      (
          'CA_ALBERTA_001',
          'Alberta forests',
          'Canada',
          54.5000, -115.0000,
          'HIGH', 'DECREASE',
          'WILDFIRE',
          'Canadian forest area to simulate surface changes after a wildfire.',
          0.948
      ),
      (
          'ID_KALIMANTAN_001',
          'Kalimantan - Borneo',
          'Indonesia',
          -1.5000, 114.0000,
          'HIGH', 'DECREASE',
          'DEFORESTATION',
          'Borneo tropical forest to simulate land cover transformation.',
          0.934
      ),
      (
          'ES_CASTILLA_001',
          'Castilla-La Mancha farmland',
          'Spain',
          39.5000, -3.0000,
          'LOW', 'INCREASE',
          'AGRICULTURE',
          'Farmland to simulate changes in moisture and crop condition.',
          0.872
      );

-- Time series observations.
-- VV and VH are illustrative values in dB, not real measurements.
-- Patterns vary with the simulated scenario.

INSERT INTO sar_observation (
    site_id, observation_date, vv, vh
) VALUES

-- 1. Valencia: simulated change in flooded surface
('ES_VALENCIA_001', '2024-10-01', -10.200, -17.400),
('ES_VALENCIA_001', '2024-10-07', -10.100, -17.300),
('ES_VALENCIA_001', '2024-10-13', -10.500, -17.600),
('ES_VALENCIA_001', '2024-10-19', -13.800, -20.100),
('ES_VALENCIA_001', '2024-10-25', -12.900, -19.300),
('ES_VALENCIA_001', '2024-10-31', -11.400, -18.100),

-- 2. Donana: seasonal moisture variation
('ES_DONANA_001', '2024-04-01', -12.100, -19.000),
('ES_DONANA_001', '2024-04-07', -12.300, -19.200),
('ES_DONANA_001', '2024-04-13', -12.700, -19.500),
('ES_DONANA_001', '2024-04-19', -13.100, -19.800),
('ES_DONANA_001', '2024-04-25', -13.500, -20.000),
('ES_DONANA_001', '2024-05-01', -13.800, -20.300),

-- 3. Cuenca: simulated change after a wildfire
('ES_CUENCA_001', '2024-06-01', -8.500, -14.200),
('ES_CUENCA_001', '2024-06-07', -8.400, -14.100),
('ES_CUENCA_001', '2024-06-13', -8.700, -14.400),
('ES_CUENCA_001', '2024-06-19', -12.600, -19.100),
('ES_CUENCA_001', '2024-06-25', -12.200, -18.700),
('ES_CUENCA_001', '2024-07-01', -11.900, -18.400),

-- 4. Amazon: simulated forest cover loss
('BR_AMAZON_001', '2024-07-01', -7.800, -13.100),
('BR_AMAZON_001', '2024-07-07', -7.900, -13.200),
('BR_AMAZON_001', '2024-07-13', -8.000, -13.100),
('BR_AMAZON_001', '2024-07-19', -11.700, -17.800),
('BR_AMAZON_001', '2024-07-25', -11.500, -17.600),
('BR_AMAZON_001', '2024-07-31', -11.200, -17.300),

-- 5. Pantanal: simulated flooding variation
('BR_PANTANAL_001', '2024-02-01', -12.500, -19.500),
('BR_PANTANAL_001', '2024-02-07', -12.000, -19.000),
('BR_PANTANAL_001', '2024-02-13', -10.500, -17.800),
('BR_PANTANAL_001', '2024-02-19', -8.900, -15.900),
('BR_PANTANAL_001', '2024-02-25', -9.500, -16.500),
('BR_PANTANAL_001', '2024-03-02', -10.800, -18.000),

-- 6. Alberta: simulated change after a forest fire
('CA_ALBERTA_001', '2024-05-01', -8.100, -13.900),
('CA_ALBERTA_001', '2024-05-07', -8.000, -13.800),
('CA_ALBERTA_001', '2024-05-13', -8.200, -14.000),
('CA_ALBERTA_001', '2024-05-19', -12.100, -18.500),
('CA_ALBERTA_001', '2024-05-25', -11.800, -18.200),
('CA_ALBERTA_001', '2024-05-31', -11.400, -17.900),

-- 7. Kalimantan: simulated forest cover loss
('ID_KALIMANTAN_001', '2024-08-01', -7.500, -12.800),
('ID_KALIMANTAN_001', '2024-08-07', -7.600, -12.900),
('ID_KALIMANTAN_001', '2024-08-13', -7.700, -13.000),
('ID_KALIMANTAN_001', '2024-08-19', -10.900, -17.100),
('ID_KALIMANTAN_001', '2024-08-25', -10.700, -16.900),
('ID_KALIMANTAN_001', '2024-08-31', -10.400, -16.600),

-- 8. Castilla-La Mancha: gradual agricultural variation
('ES_CASTILLA_001', '2024-03-01', -11.800, -19.200),
('ES_CASTILLA_001', '2024-03-07', -11.400, -18.900),
('ES_CASTILLA_001', '2024-03-13', -10.900, -18.300),
('ES_CASTILLA_001', '2024-03-19', -10.500, -17.900),
('ES_CASTILLA_001', '2024-03-25', -10.000, -17.500),
('ES_CASTILLA_001', '2024-03-31', -9.700, -17.200);
