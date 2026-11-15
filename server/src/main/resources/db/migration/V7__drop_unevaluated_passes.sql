-- Passes that cover too little of an area to be evaluated are no longer kept: their routes, Sentinel-1 agreement,
-- counts and changes go, and so do their rasters (no longer referenced, the raster sweep deletes them). The pass row
-- stays, without results, as the record that the pass was assessed: the pass search then never stages it again and
-- the public API never shows it (JdbcPassRepository.saveUnevaluated does the same for the passes to come).
DELETE c FROM pass_change c
    JOIN radar_pass p ON p.area_id = c.area_id AND (p.pass_id = c.pass_id OR p.pass_id = c.since_pass_id)
    WHERE p.assessed_at IS NOT NULL AND p.evaluated = FALSE;
DELETE a FROM connectivity_assessment a
    JOIN radar_pass p ON p.area_id = a.area_id AND p.pass_id = a.pass_id
    WHERE p.assessed_at IS NOT NULL AND p.evaluated = FALSE;
DELETE s FROM pass_sentinel1 s
    JOIN radar_pass p ON p.area_id = s.area_id AND p.pass_id = s.pass_id
    WHERE p.assessed_at IS NOT NULL AND p.evaluated = FALSE;
DELETE k FROM pass_counts k
    JOIN radar_pass p ON p.area_id = k.area_id AND p.pass_id = k.pass_id
    WHERE p.assessed_at IS NOT NULL AND p.evaluated = FALSE;
UPDATE radar_pass SET shown = FALSE, water_km2 = NULL, narrow_km2 = NULL, vegetated_km2 = NULL,
    threshold_source = NULL, threshold_strong_db = NULL, threshold_weak_db = NULL, threshold_measured_db = NULL,
    threshold_tiles = NULL, river_level_cm = NULL, low_water = NULL, hub_status = NULL, hub_bottleneck_m = NULL,
    hub_distance_to_water_m = NULL, stats_seconds = NULL, stats_spacing_m = NULL, water_raster_id = NULL,
    radar_raster_id = NULL, sentinel1_raster_id = NULL, sentinel1_vv_raster_id = NULL
    WHERE assessed_at IS NOT NULL AND evaluated = FALSE;
