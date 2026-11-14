package com.nisarsar.ui;

import com.nisarsar.common.Constants;
import com.nisarsar.dao.model.Connectivity.CommunityHistory;
import com.nisarsar.dao.model.Connectivity.Overview;
import com.nisarsar.dao.model.Connectivity.PassSummary;
import com.nisarsar.domain.services.ConnectivityService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The public monitor API: which communities around the hub can still reach it by water. */
@RestController
@RequestMapping(Constants.API)
@RequiredArgsConstructor
public class ConnectivityController {
    private final ConnectivityService connectivity;

    @GetMapping(Constants.COMMUNITIES)
    public Overview overview() {
        return connectivity.overview();
    }

    @GetMapping(Constants.COMMUNITIES + "/{id}")
    public CommunityHistory community(@PathVariable String id) {
        return connectivity
                .history(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Community not found: " + id));
    }

    @GetMapping(Constants.PASSES)
    public List<PassSummary> passes() {
        return connectivity.passes();
    }
}
