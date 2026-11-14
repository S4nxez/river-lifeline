package com.riverlifeline.monitoring.infrastructure.adapter.in.web;

import com.riverlifeline.monitoring.application.port.in.AoiQueries;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.AoiListItemResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.AoiResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.CatalogVersionResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.ListResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.RiverLevelResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.SummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** #3 aois, #4 aoi, #5 catalog-version, #6 summary and #11 river level. */
@RestController
public class AoiController {
    private final AoiQueries queries;

    public AoiController(AoiQueries queries) {
        this.queries = queries;
    }

    @GetMapping(path = ApiPaths.BASE + "/aois", produces = ApiPaths.JSON)
    public ListResponse<AoiListItemResponse> aois() {
        return ListResponse.of(
                queries.listAois().stream().map(ApiMapper::aoiListItem).toList());
    }

    @GetMapping(path = ApiPaths.AOI, produces = ApiPaths.JSON)
    public AoiResponse aoi(@PathVariable String aoiId) {
        return ApiMapper.aoi(queries.getAoi(aoiId));
    }

    @GetMapping(path = ApiPaths.AOI + "/catalog-version", produces = ApiPaths.JSON)
    public CatalogVersionResponse catalogVersion(@PathVariable String aoiId) {
        return ApiMapper.catalogVersion(queries.catalogVersion(aoiId));
    }

    @GetMapping(path = ApiPaths.AOI + "/summary", produces = ApiPaths.JSON)
    public SummaryResponse summary(@PathVariable String aoiId, @RequestParam(required = false) String date) {
        return ApiMapper.summary(queries.summary(aoiId, QueryParams.date("date", date)));
    }

    @GetMapping(path = ApiPaths.AOI + "/river-level", produces = ApiPaths.JSON)
    public RiverLevelResponse riverLevel(
            @PathVariable String aoiId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiMapper.riverLevel(
                queries.riverLevel(aoiId, QueryParams.date("from", from), QueryParams.date("to", to)));
    }
}
