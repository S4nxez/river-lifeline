package com.riverlifeline.pipeline.infrastructure;

import com.riverlifeline.pipeline.application.port.out.RasterSink;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps every raster a source adapter writes, body and layout. */
public final class CapturingSink implements RasterSink {
    public final Map<RasterId, RasterMeta> metas = new LinkedHashMap<>();
    public final Map<RasterId, byte[]> bodies = new LinkedHashMap<>();

    @Override
    public RasterRef write(RasterId id, RasterMeta meta, Body body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);
        metas.put(id, meta);
        bodies.put(id, out.toByteArray());
        return new RasterRef(id, meta);
    }

    /** A float32 body as {@code [row][col]}. */
    public float[][] floats(RasterRef ref) {
        RasterMeta meta = ref.meta();
        ByteBuffer b = ByteBuffer.wrap(bodies.get(ref.id())).order(ByteOrder.LITTLE_ENDIAN);
        float[][] out = new float[meta.height()][meta.width()];
        for (int r = 0; r < meta.height(); r++) {
            for (int c = 0; c < meta.width(); c++) {
                out[r][c] = b.getFloat();
            }
        }
        return out;
    }
}
