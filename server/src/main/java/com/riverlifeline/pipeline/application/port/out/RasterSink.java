package com.riverlifeline.pipeline.application.port.out;

import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Where a source adapter writes a raster it read: the adapter knows the layout, the sink stores the body and its
 * description. The body written must be exactly {@code meta.byteLength()} bytes long.
 */
public interface RasterSink {
    RasterRef write(RasterId id, RasterMeta meta, Body body) throws IOException;

    default RasterRef write(RasterMeta meta, Body body) throws IOException {
        return write(RasterId.random(), meta, body);
    }

    @FunctionalInterface
    interface Body {
        void writeTo(OutputStream out) throws IOException;
    }
}
