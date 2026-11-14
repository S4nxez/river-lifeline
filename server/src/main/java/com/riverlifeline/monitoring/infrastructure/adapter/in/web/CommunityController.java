package com.riverlifeline.monitoring.infrastructure.adapter.in.web;

import com.riverlifeline.monitoring.application.port.in.CommunityQueries;
import com.riverlifeline.monitoring.domain.model.pass.PassSelector;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.CommunityResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** #10 community card and history. */
@RestController
public class CommunityController {
    private final CommunityQueries queries;

    public CommunityController(CommunityQueries queries) {
        this.queries = queries;
    }

    @GetMapping(path = ApiPaths.AOI + "/communities/{communityId}", produces = ApiPaths.JSON)
    public CommunityResponse community(
            @PathVariable String aoiId,
            @PathVariable String communityId,
            @RequestParam(name = "pass_id", required = false) String passId) {
        return ApiMapper.community(queries.getCommunity(aoiId, communityId, PassSelector.parse(passId)));
    }
}
