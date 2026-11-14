package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.domain.model.area.AreaGrid;
import com.riverlifeline.pipeline.domain.model.job.JobInputs.GranuleWindow;
import com.riverlifeline.pipeline.domain.model.pass.GranuleRef;
import java.io.IOException;
import java.util.Optional;

/** Reads an area's window of a NISAR GCOV granule (HH and, when present, HV) into the sink. */
public interface GranuleWindows {
    /** Whether the products can be read at all (the Earthdata token is configured). */
    boolean available();

    /** The window; empty when the granule does not reach the area or has no HH. */
    Optional<GranuleWindow> read(GranuleRef granule, AreaGrid grid, RasterSink sink) throws IOException;
}
