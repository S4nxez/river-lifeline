package com.riverlifeline.monitoring.domain.model.community;

import com.riverlifeline.monitoring.domain.model.status.Status;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.ToIntFunction;

/** How the community list is sorted. Ties are always broken by name, then id, so the order is stable. */
public enum CommunitySort {
    /** Worst status first (the status table's {@code order}), then name. The default. */
    STATUS("status"),
    NAME("name"),
    /** Narrowest route first: no route at all, then 40 m, 60 m... then name. */
    WIDTH("width");

    private final String code;

    CommunitySort(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<CommunitySort> fromCode(String code) {
        return Arrays.stream(values()).filter(sort -> sort.code.equals(code)).findFirst();
    }

    public Comparator<CommunitySnapshot> comparator(ToIntFunction<Status> statusOrder) {
        Comparator<CommunitySnapshot> byName = Comparator.comparing((CommunitySnapshot snapshot) ->
                        SearchText.normalize(snapshot.community().name()))
                .thenComparing(snapshot -> snapshot.community().id().value());
        return switch (this) {
            case STATUS ->
                Comparator.comparingInt((CommunitySnapshot snapshot) ->
                                statusOrder.applyAsInt(snapshot.assessment().status()))
                        .thenComparing(byName);
            case NAME -> byName;
            case WIDTH ->
                Comparator.comparing(
                                (CommunitySnapshot snapshot) ->
                                        snapshot.assessment().minWidthClassM(),
                                Comparator.nullsFirst(Comparator.<Integer>naturalOrder()))
                        .thenComparing(byName);
        };
    }
}
