package com.nisarsar.dao.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Whether a community can still reach the hub by water, according to one radar pass. */
public enum ConnectivityStatus {
    /** A water route wide enough for the usual boats. */
    CONNECTED,
    /** A route remains, but narrower than the usual boats need. */
    AT_RISK,
    /** No water route left. */
    ISOLATED,
    /** The pass did not cover the community or its route. */
    NO_DATA;

    @JsonValue
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
