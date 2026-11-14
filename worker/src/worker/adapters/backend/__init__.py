"""The backend's internal worker API (api/worker-api.md). The worker never calls external services (Earthdata,
Planetary Computer, weather APIs): the backend does, and hands everything over in the job or as raw rasters."""
