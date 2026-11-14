package com.riverlifeline.pipeline.domain.model.area;

/** The official river gauge of an area (ANA station code). */
public record GaugeStation(String name, String code) {
    public GaugeStation {
        if (code == null || !code.matches("[0-9]{1,20}")) {
            throw new IllegalArgumentException("Invalid gauge station code: " + code);
        }
    }
}
