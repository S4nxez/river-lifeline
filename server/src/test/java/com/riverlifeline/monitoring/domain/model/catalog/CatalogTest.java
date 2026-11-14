package com.riverlifeline.monitoring.domain.model.catalog;

import static com.riverlifeline.monitoring.TestData.catalog;
import static com.riverlifeline.monitoring.TestData.pass;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riverlifeline.monitoring.TestData;
import com.riverlifeline.monitoring.domain.exception.CatalogSchemaUnsupportedException;
import com.riverlifeline.monitoring.domain.exception.PassNotFoundException;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.pass.PassSelector;
import com.riverlifeline.monitoring.domain.model.status.Status;
import com.riverlifeline.monitoring.domain.model.status.StatusDefinition;
import com.riverlifeline.shared.domain.SchemaVersion;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogTest {
    private final Pass july = pass("nisar-20260704t214011z-t154-f088", "2026-07-04T21:40:11Z", true);
    private final Pass october = pass("nisar-20261015t214033z-t154-f088", "2026-10-15T21:40:33Z", true);
    private final Pass partial = pass("nisar-20261019t094455z-t081-f013", "2026-10-19T09:44:55Z", false);

    @Test
    void passesAreSortedOldestFirstWhateverTheFileOrder() {
        Catalog catalog = catalog(partial, july, october);

        assertThat(catalog.passes()).containsExactly(july, october, partial);
        assertThat(catalog.evaluatedPasses()).containsExactly(july, october);
    }

    @Test
    void latestIsTheLatestEvaluatedPassNotTheLatestPass() {
        Catalog catalog = catalog(july, october, partial);

        assertThat(catalog.latestPass()).contains(partial);
        assertThat(catalog.latestEvaluatedPass()).contains(october);
        assertThat(catalog.find(PassSelector.LATEST)).contains(october);
    }

    @Test
    void aPassIsFoundByIdAndAnUnknownOrMalformedIdIsNotFound() {
        Catalog catalog = catalog(july, october);

        assertThat(catalog.find(PassSelector.parse(july.id().value()))).contains(july);
        assertThat(catalog.find(PassSelector.parse("nisar-20990101t000000z-t001-f001")))
                .isEmpty();
        assertThat(catalog.find(PassSelector.parse("../../etc/passwd"))).isEmpty();
        assertThatThrownBy(() -> catalog.require(PassSelector.parse("nope")))
                .isInstanceOf(PassNotFoundException.class)
                .hasMessage("Pass 'nope' does not exist in AOI 'tefe'.");
    }

    @Test
    void latestWithoutAnyEvaluatedPassIsNotFound() {
        assertThatThrownBy(() -> catalog(partial).require(PassSelector.LATEST))
                .isInstanceOf(PassNotFoundException.class)
                .hasMessageContaining("no evaluated pass");
    }

    @Test
    void neighboursComeFromTheFullListEvaluatedOrNot() {
        Catalog catalog = catalog(july, partial, october);

        assertThat(catalog.previousOf(july)).isEmpty();
        assertThat(catalog.nextOf(july)).contains(october);
        assertThat(catalog.previousOf(partial)).contains(october);
        assertThat(catalog.nextOf(partial)).isEmpty();
    }

    @Test
    void theSummaryPassIsTheLatestEvaluatedOnOrBeforeTheDay() {
        Catalog catalog = catalog(july, october, partial);

        assertThat(catalog.latestEvaluatedPassOnOrBefore(LocalDate.parse("2026-10-15")))
                .contains(october);
        assertThat(catalog.latestEvaluatedPassOnOrBefore(LocalDate.parse("2026-10-14")))
                .contains(july);
        assertThat(catalog.latestEvaluatedPassOnOrBefore(LocalDate.parse("2026-10-30")))
                .contains(october);
        assertThat(catalog.latestEvaluatedPassOnOrBefore(LocalDate.parse("2026-01-01")))
                .isEmpty();
    }

    @Test
    void onlyTheSupportedMajorIsAccepted() {
        assertThatThrownBy(() -> new Catalog(
                        SchemaVersion.parse("2.0.0"), Instant.now(), TestData.aoi(), List.of(), null, List.of()))
                .isInstanceOf(CatalogSchemaUnsupportedException.class)
                .hasMessageContaining("2.0.0");
        assertThat(new Catalog(SchemaVersion.parse("1.4.2"), null, TestData.aoi(), List.of(), null, List.of())
                        .schemaVersion())
                .hasToString("1.4.2");
    }

    @Test
    void theStatusTableOrderComesFromTheCatalogAndMissingRowsAreFilledIn() {
        Catalog catalog = new Catalog(
                SchemaVersion.parse("1.0.0"),
                null,
                TestData.aoi(),
                List.of(new StatusDefinition(Status.NO_DATA, "No data", "#000", "ring", -1, "first")),
                null,
                List.of());

        assertThat(catalog.statusTable()).hasSize(4);
        assertThat(catalog.statusTable().getFirst().status()).isEqualTo(Status.NO_DATA);
        assertThat(catalog.orderOf(Status.NO_DATA)).isEqualTo(-1);
        assertThat(catalog.orderOf(Status.ISOLATED)).isZero();
    }

    @Test
    void aPassListedTwiceIsRejected() {
        assertThatThrownBy(() -> catalog(july, july)).isInstanceOf(IllegalArgumentException.class);
    }
}
