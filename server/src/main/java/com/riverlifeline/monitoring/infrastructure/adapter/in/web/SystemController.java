package com.riverlifeline.monitoring.infrastructure.adapter.in.web;

import com.riverlifeline.monitoring.application.port.in.SystemQueries;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.HealthResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.MetaResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** #1 health (always 200) and #2 meta. */
@RestController
public class SystemController {
    private final SystemQueries queries;

    public SystemController(SystemQueries queries) {
        this.queries = queries;
    }

    @GetMapping(path = ApiPaths.HEALTH, produces = ApiPaths.JSON)
    public HealthResponse health() {
        return ApiMapper.health(queries.health());
    }

    @GetMapping(path = ApiPaths.BASE + "/meta", produces = ApiPaths.JSON)
    public MetaResponse meta() {
        return ApiMapper.meta(queries.meta());
    }
}
