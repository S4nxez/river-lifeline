package com.riverlifeline.monitoring.domain.model.community;

import com.riverlifeline.monitoring.domain.model.status.Status;
import java.util.EnumSet;
import java.util.Set;

/**
 * Which communities of a pass to list.
 *
 * @param statuses only these statuses; empty means every status
 * @param changedOnly only communities whose status changed since the previous evaluated pass
 * @param nameQuery a case- and accent-insensitive part of the name; null or blank means any name
 */
public record CommunityFilter(Set<Status> statuses, boolean changedOnly, String nameQuery) {
    public static final CommunityFilter ALL = new CommunityFilter(Set.of(), false, null);

    public CommunityFilter {
        statuses = statuses == null || statuses.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(statuses));
    }

    public boolean matches(CommunitySnapshot snapshot) {
        if (!statuses.isEmpty() && !statuses.contains(snapshot.assessment().status())) {
            return false;
        }
        if (changedOnly && !snapshot.assessment().changed()) {
            return false;
        }
        return nameQuery == null
                || nameQuery.isBlank()
                || SearchText.contains(snapshot.community().name(), nameQuery);
    }
}
