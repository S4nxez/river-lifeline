package com.riverlifeline.monitoring.application.port.in;

import com.riverlifeline.monitoring.application.view.Views.LayerContent;
import com.riverlifeline.monitoring.application.view.Views.PassDetail;
import com.riverlifeline.monitoring.application.view.Views.PassEntry;
import com.riverlifeline.monitoring.domain.model.pass.PassSelector;
import java.time.LocalDate;
import java.util.List;

/** Endpoints #7, #8 and #12. */
public interface PassQueries {

    /**
     * Passes oldest first.
     *
     * @param evaluated only evaluated (true) or not evaluated (false) passes; null for both
     * @param from first acquisition day (UTC), inclusive; null for no bound
     * @param to last acquisition day (UTC), inclusive; null for no bound
     */
    List<PassEntry> listPasses(String aoiId, Boolean evaluated, LocalDate from, LocalDate to);

    PassDetail getPass(String aoiId, PassSelector selector);

    LayerContent layer(String aoiId, PassSelector selector, String layerId);
}
