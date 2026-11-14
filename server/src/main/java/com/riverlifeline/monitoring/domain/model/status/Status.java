package com.riverlifeline.monitoring.domain.model.status;

import java.util.Arrays;
import java.util.Optional;

/** Whether a community can still reach the reference port by water (CONTRACTS.md 1.1). */
public enum Status {
    ISOLATED("isolated", 0),
    AT_RISK("at_risk", 1),
    CONNECTED("connected", 2),
    NO_DATA("no_data", 3);

    private final String code;
    private final int defaultOrder;

    Status(String code, int defaultOrder) {
        this.code = code;
        this.defaultOrder = defaultOrder;
    }

    public String code() {
        return code;
    }

    /** Worst first. The catalog's status table may override it. */
    public int defaultOrder() {
        return defaultOrder;
    }

    public static Optional<Status> fromCode(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code.equals(code))
                .findFirst();
    }
}
