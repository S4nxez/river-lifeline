package com.riverlifeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * River Lifeline backend, in two bounded contexts: {@code monitoring}, the read-only public API (CONTRACTS.md, contract
 * 2) over the monitor's files, and {@code pipeline}, the internal worker API (api/worker-api.md) with the external
 * sources the worker needs and the store of its results. It never computes science.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RiverLifelineApplication {
    public static void main(String[] args) {
        SpringApplication.run(RiverLifelineApplication.class, args);
    }
}
