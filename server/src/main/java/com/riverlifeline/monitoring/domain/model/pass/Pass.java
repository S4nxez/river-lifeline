package com.riverlifeline.monitoring.domain.model.pass;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One satellite acquisition over the AOI. Only an evaluated pass (coverage at least {@code min_coverage_fraction})
 * has community statuses. A pass never changes once it is in the catalog.
 */
public record Pass(
        PassId id,
        Instant acquiredAt,
        String platform,
        String band,
        Double wavelengthCm,
        String product,
        String granuleId,
        Integer track,
        Integer frame,
        Integer absoluteOrbit,
        OrbitDirection orbitDirection,
        List<String> polarizations,
        Double pixelSizeM,
        Double coverageFraction,
        boolean evaluated,
        Double riverLevelM,
        LowWaterRule lowWaterRule,
        StatusCounts counts,
        Instant processedAt,
        String methodVersion,
        String statusesPath,
        Map<LayerId, LayerImage> layers) {

    public Pass {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        polarizations = polarizations == null ? List.of() : List.copyOf(polarizations);
        lowWaterRule = lowWaterRule == null ? LowWaterRule.INACTIVE : lowWaterRule;
        counts = counts == null ? StatusCounts.notEvaluated(null) : counts;
        Map<LayerId, LayerImage> ordered = new EnumMap<>(LayerId.class);
        if (layers != null) {
            ordered.putAll(layers);
        }
        layers = Collections.unmodifiableMap(ordered);
    }

    /** The acquisition day in UTC. */
    public LocalDate acquiredOn() {
        return acquiredAt.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    public Optional<LayerImage> layer(LayerId layerId) {
        return Optional.ofNullable(layers.get(layerId));
    }

    public SatelliteMission mission() {
        return SatelliteMission.forPlatform(platform);
    }
}
