package com.riverlifeline.pipeline.domain.model.raster;

/** Why a raster body is kept, which decides when it is deleted. */
public enum RasterRole {
    /** Read from a product for one job; deleted once no unfinished job references it. */
    INPUT,
    /** An area's water occurrence; kept. */
    OCCURRENCE,
    /** Uploaded by the worker, waiting for the result of its lease. */
    PENDING,
    /** Part of an accepted result; kept while a pass or a change references it. */
    RESULT
}
