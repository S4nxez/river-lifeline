-- The Sentinel-1 image of a pass (api/worker-api.md, result raster sentinel1_vv): the darkest filtered VV of the
-- scenes the pass was judged with, in dB at half the area grid, painted for the page as the radar image is.
ALTER TABLE radar_pass ADD COLUMN sentinel1_vv_raster_id VARCHAR(128) NULL AFTER sentinel1_raster_id;
