package com.riverlifeline.monitoring.application.port.in;

import com.riverlifeline.monitoring.application.view.Views.Health;
import com.riverlifeline.monitoring.application.view.Views.Meta;

/** Endpoints #1 health and #2 meta. */
public interface SystemQueries {

    /** Never fails: a missing or invalid catalog is reported, not thrown. */
    Health health();

    Meta meta();
}
