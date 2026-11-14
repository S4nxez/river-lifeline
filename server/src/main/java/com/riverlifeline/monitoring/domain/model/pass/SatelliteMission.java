package com.riverlifeline.monitoring.domain.model.pass;

/** Who flies the sensor of a pass and where its data is public, for the image card. */
public record SatelliteMission(String satellite, String sensor, String dataSourceUrl) {
    public static final SatelliteMission NISAR =
            new SatelliteMission("NISAR (NASA–ISRO)", "L-SAR", "https://search.asf.alaska.edu/");

    public static SatelliteMission forPlatform(String platform) {
        if ("NISAR".equalsIgnoreCase(platform)) {
            return NISAR;
        }
        return new SatelliteMission(platform, null, null);
    }
}
