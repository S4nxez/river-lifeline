package com.riverlifeline.monitoring.infrastructure.adapter.in.web;

import com.riverlifeline.monitoring.application.port.in.EarlyWarningQueries;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The experimental early warning of an AOI, as published (CONTRACTS.md); 404 when it has none. */
@RestController
public class EarlyWarningController {
    private final EarlyWarningQueries queries;

    public EarlyWarningController(EarlyWarningQueries queries) {
        this.queries = queries;
    }

    @GetMapping(path = ApiPaths.AOI + "/early-warning", produces = ApiPaths.JSON)
    public ResponseEntity<String> earlyWarning(@PathVariable String aoiId) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(queries.earlyWarning(aoiId).json());
    }
}
