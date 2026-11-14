package com.riverlifeline.monitoring.infrastructure.adapter.in.web;

import com.riverlifeline.monitoring.application.exception.InvalidParameterException;
import com.riverlifeline.monitoring.application.port.in.CommunityQueries;
import com.riverlifeline.monitoring.application.port.in.PassQueries;
import com.riverlifeline.monitoring.application.view.Views.LayerContent;
import com.riverlifeline.monitoring.application.view.Views.PassCommunities;
import com.riverlifeline.monitoring.domain.model.community.CommunityFilter;
import com.riverlifeline.monitoring.domain.model.community.CommunitySort;
import com.riverlifeline.monitoring.domain.model.pass.PassSelector;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.GeoJsonExportResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.ListResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.PassCommunitiesResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.PassDetailResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.dto.ApiResponses.PassResponse;
import com.riverlifeline.monitoring.infrastructure.adapter.in.web.export.CsvExport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** #7 passes, #8 pass, #9 communities on a pass, #12 layer PNG and #13 export. */
@RestController
public class PassController {
    static final String ATTRIBUTION = "Contains NISAR data (NASA/ISRO) via ASF; Sentinel-1 (ESA); JRC GSW; "
            + "© OpenStreetMap contributors; ANA.";
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final MediaType GEOJSON = new MediaType("application", "geo+json", StandardCharsets.UTF_8);

    private final PassQueries passes;
    private final CommunityQueries communities;

    public PassController(PassQueries passes, CommunityQueries communities) {
        this.passes = passes;
        this.communities = communities;
    }

    @GetMapping(path = ApiPaths.AOI + "/passes", produces = ApiPaths.JSON)
    public ListResponse<PassResponse> passes(
            @PathVariable String aoiId,
            @RequestParam(required = false) String evaluated,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ListResponse.of(passes
                .listPasses(
                        aoiId,
                        QueryParams.bool("evaluated", evaluated),
                        QueryParams.date("from", from),
                        QueryParams.date("to", to))
                .stream()
                .map(ApiMapper::pass)
                .toList());
    }

    @GetMapping(path = ApiPaths.PASS, produces = ApiPaths.JSON)
    public PassDetailResponse pass(@PathVariable String aoiId, @PathVariable String passId) {
        return ApiMapper.passDetail(passes.getPass(aoiId, PassSelector.parse(passId)));
    }

    @GetMapping(path = ApiPaths.PASS + "/communities", produces = ApiPaths.JSON)
    public PassCommunitiesResponse communities(
            @PathVariable String aoiId,
            @PathVariable String passId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String changed,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort) {
        CommunityFilter filter = QueryParams.filter(status, changed, q);
        return ApiMapper.passCommunities(
                communities.listOnPass(aoiId, PassSelector.parse(passId), filter, QueryParams.sort(sort)));
    }

    @GetMapping(path = ApiPaths.PASS + "/layers/{layerId}.png")
    public ResponseEntity<InputStreamResource> layer(
            @PathVariable String aoiId, @PathVariable String passId, @PathVariable String layerId) throws IOException {
        LayerContent layer = passes.layer(aoiId, PassSelector.parse(passId), layerId);
        // A pass never changes, so its PNG can be cached forever; "latest" can point to another pass tomorrow
        CacheControl cache = layer.immutable()
                ? CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()
                : CacheControl.noCache();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(layer.content().sizeBytes())
                .cacheControl(cache)
                .body(new InputStreamResource(layer.content().open()));
    }

    @GetMapping(path = ApiPaths.PASS + "/export")
    public ResponseEntity<?> export(
            @PathVariable String aoiId, @PathVariable String passId, @RequestParam(required = false) String format) {
        if (!"csv".equals(format) && !"geojson".equals(format)) {
            throw new InvalidParameterException("format", "'format' must be csv or geojson, got '" + format + "'.");
        }
        PassCommunities view =
                communities.listOnPass(aoiId, PassSelector.parse(passId), CommunityFilter.ALL, CommunitySort.STATUS);
        String filename = "river-lifeline_%s_%s_%s.%s"
                .formatted(view.aoiId(), view.pass().acquiredOn(), view.pass().id(), format);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename).build());
        if ("csv".equals(format)) {
            return ResponseEntity.ok()
                    .headers(headers)
                    .contentType(CSV)
                    .body(CsvExport.write(view).getBytes(StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(GEOJSON)
                .body(new GeoJsonExportResponse(ApiMapper.passCommunities(view), ATTRIBUTION));
    }
}
