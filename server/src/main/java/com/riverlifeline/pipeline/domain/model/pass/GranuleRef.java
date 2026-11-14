package com.riverlifeline.pipeline.domain.model.pass;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * One NISAR L2 GCOV frame product as ASF lists it. The fields come from its file name:
 * {@code NISAR_L2_PR_GCOV_<cycle>_<track>_<A|D>_<frame>_<mode>_<pol>_<..>_<start>_...h5}.
 */
public record GranuleRef(
        String name,
        String url,
        String collection,
        int cycle,
        int track,
        Direction direction,
        int frame,
        String mode,
        String polarization,
        LocalDateTime start) {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    public GranuleRef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(polarization, "polarization");
        Objects.requireNonNull(start, "start");
    }

    /** The granule a file name describes; {@code IllegalArgumentException} when it is not a GCOV name. */
    public static GranuleRef parse(String name, String url, String collection) {
        String[] p = name.split("_");
        if (p.length < 12 || !"GCOV".equals(p[3]) || p[6].length() != 1) {
            throw new IllegalArgumentException("Not a NISAR GCOV file name: " + name);
        }
        try {
            return new GranuleRef(
                    name,
                    url,
                    collection,
                    Integer.parseInt(p[4]),
                    Integer.parseInt(p[5]),
                    Direction.fromLetter(p[6].charAt(0)),
                    Integer.parseInt(p[7]),
                    p[8],
                    p[9],
                    LocalDateTime.parse(p[11], STAMP));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a NISAR GCOV file name: " + name, e);
        }
    }
}
