package com.riverlifeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * River Lifeline backend: a read-only HTTP API (CONTRACTS.md, contract 2) over the files the processing pipeline
 * writes (contract 1). It never computes science: it reads, validates, joins, filters and serves.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RiverLifelineApplication {
    public static void main(String[] args) {
        SpringApplication.run(RiverLifelineApplication.class, args);
    }
}
