package com.riverlifeline.monitoring.domain.model.community;

import com.riverlifeline.monitoring.domain.model.Identifier;

/** {@code osm-<node|way|relation>-<osm id>}, or {@code manual-<slug>} for a community added by hand. */
public record CommunityId(String value) {
    public CommunityId {
        Identifier.require(value, "community");
    }

    public static CommunityId of(String value) {
        return new CommunityId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
