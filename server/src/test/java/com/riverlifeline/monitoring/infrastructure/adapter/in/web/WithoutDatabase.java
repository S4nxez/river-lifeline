package com.riverlifeline.monitoring.infrastructure.adapter.in.web;

/**
 * The application connects to MySQL and runs Flyway on startup; unit tests never need a database (AGENTS.md), so the
 * Spring tests leave that auto-configuration out. The API itself never reads the database.
 */
final class WithoutDatabase {
    static final String PROPERTY = "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration";

    private WithoutDatabase() {}
}
