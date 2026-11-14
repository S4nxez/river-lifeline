package com.nisarsar.dao.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum CommunityKind {
    /** The town every route leads to (Tefé): not assessed itself. */
    HUB,
    TOWN,
    VILLAGE;

    @JsonValue
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
