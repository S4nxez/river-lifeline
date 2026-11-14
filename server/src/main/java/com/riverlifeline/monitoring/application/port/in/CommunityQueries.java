package com.riverlifeline.monitoring.application.port.in;

import com.riverlifeline.monitoring.application.view.Views.CommunityDetail;
import com.riverlifeline.monitoring.application.view.Views.PassCommunities;
import com.riverlifeline.monitoring.domain.model.community.CommunityFilter;
import com.riverlifeline.monitoring.domain.model.community.CommunitySort;
import com.riverlifeline.monitoring.domain.model.pass.PassSelector;

/** Endpoints #9, #10 and #13 (the export lists a pass's communities without filters). */
public interface CommunityQueries {

    /** The monitored communities of a pass, joined with their status on it. */
    PassCommunities listOnPass(String aoiId, PassSelector selector, CommunityFilter filter, CommunitySort sort);

    /** A community, its status on the selected pass and its history over the evaluated passes. */
    CommunityDetail getCommunity(String aoiId, String communityId, PassSelector selector);
}
