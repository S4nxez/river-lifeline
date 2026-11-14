package com.riverlifeline.monitoring.domain.model.catalog;

import com.riverlifeline.monitoring.domain.exception.CatalogSchemaUnsupportedException;
import com.riverlifeline.monitoring.domain.exception.PassNotFoundException;
import com.riverlifeline.monitoring.domain.model.aoi.Aoi;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.pass.PassId;
import com.riverlifeline.monitoring.domain.model.pass.PassSelector;
import com.riverlifeline.monitoring.domain.model.status.Status;
import com.riverlifeline.monitoring.domain.model.status.StatusDefinition;
import com.riverlifeline.shared.domain.SchemaVersion;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root: everything the processing pipeline published for one AOI ({@code catalog.json}). It holds the AOI,
 * how statuses are shown and the passes, sorted by acquisition time.
 */
public final class Catalog {
    /** The only {@code schema_version} major this backend reads. */
    public static final int SUPPORTED_SCHEMA_MAJOR = 1;

    /** Fills the rows a catalog leaves out. Thresholds are per AOI, so these texts name none. */
    private static final List<StatusDefinition> DEFAULT_STATUS_TABLE = List.of(
            new StatusDefinition(
                    Status.ISOLATED,
                    "Isolated",
                    "#E5484D",
                    "square",
                    0,
                    "No water route on 2 consecutive evaluated passes."),
            new StatusDefinition(
                    Status.AT_RISK, "At risk", "#F5A524", "triangle", 1, "Only narrow or unverified routes left."),
            new StatusDefinition(
                    Status.CONNECTED, "Connected", "#30A46C", "circle", 2, "Verified route at least the safe width."),
            new StatusDefinition(
                    Status.NO_DATA, "No data", "#8B8D98", "ring", 3, "Not covered by this pass or not evaluable."));

    private final SchemaVersion schemaVersion;
    private final Instant generatedAt;
    private final Aoi aoi;
    private final List<StatusDefinition> statusTable;
    private final Map<Status, Integer> orderByStatus;
    private final MethodInfo method;
    private final List<Pass> passes;
    private final Map<PassId, Integer> indexById;

    public Catalog(
            SchemaVersion schemaVersion,
            Instant generatedAt,
            Aoi aoi,
            List<StatusDefinition> statusTable,
            MethodInfo method,
            List<Pass> passes) {
        this.aoi = Objects.requireNonNull(aoi, "aoi");
        requireSupported(aoi.id(), Objects.requireNonNull(schemaVersion, "schemaVersion"));
        this.schemaVersion = schemaVersion;
        this.generatedAt = generatedAt;
        this.method = method;
        this.statusTable = completeStatusTable(statusTable == null ? List.of() : statusTable);
        this.orderByStatus = new EnumMap<>(Status.class);
        this.statusTable.forEach(definition -> orderByStatus.put(definition.status(), definition.order()));

        List<Pass> sorted = new ArrayList<>(passes == null ? List.of() : passes);
        sorted.sort(Comparator.comparing(Pass::acquiredAt));
        this.passes = List.copyOf(sorted);
        Map<PassId, Integer> index = new HashMap<>();
        for (int i = 0; i < this.passes.size(); i++) {
            if (index.put(this.passes.get(i).id(), i) != null) {
                throw new IllegalArgumentException("Pass " + this.passes.get(i).id() + " is listed twice");
            }
        }
        this.indexById = Map.copyOf(index);
    }

    /** Fails with {@link CatalogSchemaUnsupportedException} unless the major version is the supported one. */
    public static void requireSupported(AoiId aoiId, SchemaVersion schemaVersion) {
        if (schemaVersion.major() != SUPPORTED_SCHEMA_MAJOR) {
            throw new CatalogSchemaUnsupportedException(aoiId, schemaVersion, SUPPORTED_SCHEMA_MAJOR);
        }
    }

    private static List<StatusDefinition> completeStatusTable(List<StatusDefinition> published) {
        Map<Status, StatusDefinition> byStatus = new EnumMap<>(Status.class);
        DEFAULT_STATUS_TABLE.forEach(definition -> byStatus.put(definition.status(), definition));
        published.forEach(definition -> byStatus.put(definition.status(), definition));
        return byStatus.values().stream()
                .sorted(Comparator.comparingInt(StatusDefinition::order))
                .toList();
    }

    public SchemaVersion schemaVersion() {
        return schemaVersion;
    }

    public Instant generatedAt() {
        return generatedAt;
    }

    public Aoi aoi() {
        return aoi;
    }

    public AoiId aoiId() {
        return aoi.id();
    }

    /** One definition per status, sorted by {@code order}. */
    public List<StatusDefinition> statusTable() {
        return statusTable;
    }

    /** The default sort position of a status (worst first). */
    public int orderOf(Status status) {
        return orderByStatus.getOrDefault(status, status.defaultOrder());
    }

    public MethodInfo method() {
        return method;
    }

    /** Every pass, evaluated or not, oldest first. */
    public List<Pass> passes() {
        return passes;
    }

    public List<Pass> evaluatedPasses() {
        return passes.stream().filter(Pass::evaluated).toList();
    }

    public Optional<Pass> latestPass() {
        return passes.isEmpty() ? Optional.empty() : Optional.of(passes.getLast());
    }

    public Optional<Pass> latestEvaluatedPass() {
        return passes.reversed().stream().filter(Pass::evaluated).findFirst();
    }

    /** The latest evaluated pass acquired on or before {@code date} (UTC). */
    public Optional<Pass> latestEvaluatedPassOnOrBefore(LocalDate date) {
        return passes.reversed().stream()
                .filter(Pass::evaluated)
                .filter(pass -> !pass.acquiredOn().isAfter(date))
                .findFirst();
    }

    /** The pass a client selected; {@code latest} is the latest evaluated pass. */
    public Optional<Pass> find(PassSelector selector) {
        if (selector.isLatest()) {
            return latestEvaluatedPass();
        }
        return selector.passId().map(indexById::get).map(passes::get);
    }

    public Pass require(PassSelector selector) {
        return find(selector).orElseThrow(() -> new PassNotFoundException(aoiId(), selector));
    }

    /** The neighbouring pass in the full list, evaluated or not. */
    public Optional<Pass> previousOf(Pass pass) {
        int index = indexOf(pass);
        return index > 0 ? Optional.of(passes.get(index - 1)) : Optional.empty();
    }

    /** The neighbouring pass in the full list, evaluated or not. */
    public Optional<Pass> nextOf(Pass pass) {
        int index = indexOf(pass);
        return index < passes.size() - 1 ? Optional.of(passes.get(index + 1)) : Optional.empty();
    }

    private int indexOf(Pass pass) {
        Integer index = indexById.get(pass.id());
        if (index == null) {
            throw new IllegalArgumentException("Pass " + pass.id() + " is not in the catalog of " + aoiId());
        }
        return index;
    }
}
