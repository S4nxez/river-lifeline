package com.riverlifeline.monitoring.infrastructure.adapter.out.pipeline;

import com.riverlifeline.monitoring.domain.model.pass.LayerId;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The path of a layer rendered from a stored raster: {@code rasters/<raster id>/<layer>.png}. */
public record LayerPath(String rasterId, LayerId layer) {
    private static final Pattern PATH = Pattern.compile("rasters/([A-Za-z0-9_-]{1,128})/([a-z_]+)\\.png");

    public static String of(String rasterId, LayerId layer) {
        return "rasters/" + rasterId + "/" + layer.code() + ".png";
    }

    /** The raster and layer a path names; empty for any other path (a file catalog's, a forged one). */
    public static Optional<LayerPath> parse(String path) {
        Matcher m = PATH.matcher(path);
        if (!m.matches()) {
            return Optional.empty();
        }
        return LayerId.fromCode(m.group(2)).map(layer -> new LayerPath(m.group(1), layer));
    }
}
