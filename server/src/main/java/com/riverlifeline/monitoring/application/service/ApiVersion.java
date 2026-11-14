package com.riverlifeline.monitoring.application.service;

/** The versions this backend speaks. */
public final class ApiVersion {
    /** The HTTP API (contract 2), reported as {@code api_version}. */
    public static final String CURRENT = "1.0.0";

    /** The contract version every JSON response announces in its {@code X-Schema-Version} header. */
    public static final String SCHEMA = "1.0.0";

    private ApiVersion() {}
}
