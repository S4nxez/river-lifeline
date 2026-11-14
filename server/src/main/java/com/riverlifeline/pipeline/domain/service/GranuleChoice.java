package com.riverlifeline.pipeline.domain.service;

import com.riverlifeline.pipeline.domain.model.pass.GranuleRef;
import com.riverlifeline.pipeline.domain.model.pass.NisarPass;
import com.riverlifeline.pipeline.domain.model.pass.PassId;
import com.riverlifeline.pipeline.domain.model.pass.PassInfo;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which granule to read for every frame, and how frames make passes. Of the granules of one frame on one day, the
 * best is the final product before the provisional one before the beta one, then 20 MHz before 40 MHz, then HH
 * (dual or single polarisation) before the others. Modes without a 20 or 40 MHz frequency A are unusable.
 */
public final class GranuleChoice {
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private GranuleChoice() {}

    /** Sort key of a granule (lower is better); empty when it cannot be used. */
    public static Optional<int[]> rank(GranuleRef granule) {
        String mode = granule.mode();
        if (mode.length() < 2 || mode.startsWith("00")) {
            return Optional.empty();
        }
        String collection = granule.collection().toUpperCase();
        int product = collection.contains("BETA") ? 2 : collection.contains("PROVISIONAL") ? 1 : 0;
        int bandwidth = switch (mode.substring(0, 2)) {
            case "20" -> 0;
            case "40" -> 1;
            default -> 2;
        };
        int polarization = switch (granule.polarization()) {
            case "DHDH", "SHSH" -> 0;
            default -> 1;
        };
        return Optional.of(new int[] {product, bandwidth, polarization});
    }

    /** The passes the granules make, oldest first, each with the best granule of each of its frames. */
    public static List<NisarPass> passes(List<GranuleRef> granules) {
        Comparator<int[]> byRank = Comparator.<int[]>comparingInt(r -> r[0])
                .thenComparingInt(r -> r[1])
                .thenComparingInt(r -> r[2]);
        Map<String, GranuleRef> best = new HashMap<>();
        Map<String, int[]> bestRank = new HashMap<>();
        for (GranuleRef granule : granules) {
            Optional<int[]> rank = rank(granule);
            if (rank.isEmpty()) {
                continue;
            }
            String frame = granule.start().toLocalDate() + "/" + granule.track() + "/" + granule.frame();
            if (!best.containsKey(frame) || byRank.compare(rank.get(), bestRank.get(frame)) < 0) {
                best.put(frame, granule);
                bestRank.put(frame, rank.get());
            }
        }
        Map<PassId, List<GranuleRef>> byPass = new LinkedHashMap<>();
        best.values().stream()
                .sorted(Comparator.comparing(GranuleRef::start).thenComparingInt(GranuleRef::frame))
                .forEach(g -> byPass.computeIfAbsent(
                                PassId.of(g.start().toLocalDate(), g.track(), g.direction()), id -> new ArrayList<>())
                        .add(g));
        List<NisarPass> passes = new ArrayList<>();
        byPass.forEach((id, frames) -> {
            frames.sort(Comparator.comparingInt(GranuleRef::frame));
            GranuleRef first =
                    frames.stream().min(Comparator.comparing(GranuleRef::start)).orElseThrow();
            LocalDate day = first.start().toLocalDate();
            passes.add(new NisarPass(
                    new PassInfo(id, day, first.start().format(HH_MM), first.track(), first.direction()), frames));
        });
        passes.sort(Comparator.comparing((NisarPass p) -> p.info().date())
                .thenComparing(p -> p.info().timeUtc())
                .thenComparing(p -> p.info().id().value()));
        return passes;
    }
}
