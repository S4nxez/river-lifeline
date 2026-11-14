package com.riverlifeline.pipeline.infrastructure.adapter.out.areafiles;

import com.riverlifeline.pipeline.application.port.out.OccurrenceSource;
import com.riverlifeline.pipeline.application.port.out.RasterSink;
import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.area.AreaGrid;
import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterId;
import com.riverlifeline.pipeline.domain.model.raster.RasterRef;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * Decodes an area's occurrence PNG (8-bit grey, percent of months with water, 255 no data) into a raw raster on the
 * area grid. The PNG must have exactly the grid's size.
 */
public class PngOccurrenceSource implements OccurrenceSource {
    private final AreaFiles files;

    public PngOccurrenceSource(AreaFiles files) {
        this.files = files;
    }

    @Override
    public Optional<RasterRef> write(Area area, RasterId id, RasterSink sink) throws IOException {
        Optional<byte[]> png = files.occurrencePng(area.id());
        if (png.isEmpty()) {
            return Optional.empty();
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png.get()));
        if (image == null) {
            throw new IOException("The occurrence of " + area.id() + " is not a readable PNG");
        }
        AreaGrid grid = area.grid();
        if (image.getWidth() != grid.width() || image.getHeight() != grid.height()) {
            throw new IOException("The occurrence of " + area.id() + " is " + image.getWidth() + " x "
                    + image.getHeight() + ", its grid is " + grid.width() + " x " + grid.height());
        }
        Raster pixels = image.getRaster();
        if (pixels.getNumBands() != 1 || pixels.getSampleModel().getSampleSize(0) != 8) {
            throw new IOException("The occurrence of " + area.id() + " must be an 8-bit grey PNG");
        }
        return Optional.of(sink.write(id, grid.meta(DataType.UINT8, NoData.value(255), RasterUnit.PERCENT), out -> {
            byte[] row = new byte[grid.width()];
            int[] samples = new int[grid.width()];
            for (int y = 0; y < grid.height(); y++) {
                pixels.getSamples(0, y, grid.width(), 1, 0, samples);
                for (int x = 0; x < samples.length; x++) {
                    row[x] = (byte) samples[x];
                }
                out.write(row);
            }
        }));
    }
}
