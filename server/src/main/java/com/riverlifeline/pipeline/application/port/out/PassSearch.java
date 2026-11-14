package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.domain.model.area.Bbox;
import com.riverlifeline.pipeline.domain.model.pass.GranuleRef;
import java.io.IOException;
import java.util.List;

/** The NISAR GCOV products over a box (ASF search). */
public interface PassSearch {
    List<GranuleRef> granules(Bbox bbox) throws IOException;
}
