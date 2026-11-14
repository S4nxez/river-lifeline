package com.riverlifeline.monitoring.domain.service;

public enum HeadlineLevel {
    INFO("info"),
    CRITICAL("critical"),
    WARNING("warning"),
    OK("ok");

    private final String code;

    HeadlineLevel(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
