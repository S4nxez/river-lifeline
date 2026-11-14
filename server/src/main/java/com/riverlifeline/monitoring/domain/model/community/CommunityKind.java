package com.riverlifeline.monitoring.domain.model.community;

import java.util.Arrays;

public enum CommunityKind {
    TOWN("town"),
    VILLAGE("village"),
    HAMLET("hamlet"),
    INDIGENOUS_VILLAGE("indigenous_village"),
    OTHER("other");

    private final String code;

    CommunityKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Unknown kinds (a newer minor version of the contract) are shown as {@code other}. */
    public static CommunityKind fromCode(String code) {
        return Arrays.stream(values())
                .filter(kind -> kind.code.equals(code))
                .findFirst()
                .orElse(OTHER);
    }
}
