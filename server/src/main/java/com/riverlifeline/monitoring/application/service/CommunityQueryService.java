package com.riverlifeline.monitoring.application.service;

import com.riverlifeline.monitoring.application.port.in.CommunityQueries;
import com.riverlifeline.monitoring.application.port.out.CatalogRepository;
import com.riverlifeline.monitoring.application.port.out.CommunityRepository;
import com.riverlifeline.monitoring.application.port.out.PassStatusRepository;
import com.riverlifeline.monitoring.application.view.Views.CommunityDetail;
import com.riverlifeline.monitoring.application.view.Views.PassAssessment;
import com.riverlifeline.monitoring.application.view.Views.PassCommunities;
import com.riverlifeline.monitoring.domain.exception.CommunityNotFoundException;
import com.riverlifeline.monitoring.domain.model.Identifier;
import com.riverlifeline.monitoring.domain.model.catalog.Catalog;
import com.riverlifeline.monitoring.domain.model.community.Community;
import com.riverlifeline.monitoring.domain.model.community.CommunityFilter;
import com.riverlifeline.monitoring.domain.model.community.CommunityId;
import com.riverlifeline.monitoring.domain.model.community.CommunitySnapshot;
import com.riverlifeline.monitoring.domain.model.community.CommunitySort;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.pass.PassSelector;
import com.riverlifeline.monitoring.domain.model.status.CommunityAssessment;
import com.riverlifeline.monitoring.domain.model.status.PassStatuses;
import com.riverlifeline.monitoring.domain.service.CommunityStatusResolver;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Joins the communities with the statuses processing published for each pass. */
public class CommunityQueryService implements CommunityQueries {
    private final Catalogs catalogs;
    private final CommunityRepository communities;
    private final PassStatusRepository statuses;

    public CommunityQueryService(
            CatalogRepository catalogs, CommunityRepository communities, PassStatusRepository statuses) {
        this.catalogs = new Catalogs(catalogs);
        this.communities = Objects.requireNonNull(communities, "communities");
        this.statuses = Objects.requireNonNull(statuses, "statuses");
    }

    @Override
    public PassCommunities listOnPass(String aoiId, PassSelector selector, CommunityFilter filter, CommunitySort sort) {
        Catalog catalog = catalogs.load(aoiId);
        Pass pass = catalog.require(selector);
        PassStatuses passStatuses = statuses.find(catalog.aoiId(), pass);
        List<CommunitySnapshot> snapshots = communities.findAll(catalog.aoiId()).stream()
                .filter(Community::monitored)
                .map(community -> new CommunitySnapshot(community, assess(catalog, pass, passStatuses, community)))
                .filter(filter::matches)
                .sorted(sort.comparator(catalog::orderOf))
                .toList();
        return new PassCommunities(catalog.aoiId(), pass, snapshots);
    }

    @Override
    public CommunityDetail getCommunity(String aoiId, String communityId, PassSelector selector) {
        Catalog catalog = catalogs.load(aoiId);
        Community community = findCommunity(catalog, communityId);
        // An explicit pass id that doesn't exist is an error; "latest" with no evaluated pass yet is not
        Optional<Pass> selected =
                selector.isLatest() ? catalog.latestEvaluatedPass() : Optional.of(catalog.require(selector));
        if (!community.monitored()) {
            return new CommunityDetail(community, null, List.of());
        }
        PassAssessment current =
                selected.map(pass -> assessmentOn(catalog, pass, community)).orElse(null);
        List<PassAssessment> history = catalog.evaluatedPasses().stream()
                .map(pass -> assessmentOn(catalog, pass, community))
                .toList();
        return new CommunityDetail(community, current, history);
    }

    private Community findCommunity(Catalog catalog, String communityId) {
        if (!Identifier.isValid(communityId)) {
            throw new CommunityNotFoundException(catalog.aoiId(), communityId);
        }
        CommunityId id = CommunityId.of(communityId);
        return communities.findAll(catalog.aoiId()).stream()
                .filter(community -> community.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new CommunityNotFoundException(catalog.aoiId(), communityId));
    }

    private PassAssessment assessmentOn(Catalog catalog, Pass pass, Community community) {
        PassStatuses passStatuses = statuses.find(catalog.aoiId(), pass);
        return new PassAssessment(pass, assess(catalog, pass, passStatuses, community));
    }

    private static CommunityAssessment assess(
            Catalog catalog, Pass pass, PassStatuses passStatuses, Community community) {
        return CommunityStatusResolver.resolve(
                pass, passStatuses, community.id(), catalog.aoi().parameters().connectedMinWidthM());
    }
}
