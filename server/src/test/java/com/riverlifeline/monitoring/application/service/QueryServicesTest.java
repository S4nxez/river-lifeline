package com.riverlifeline.monitoring.application.service;

import static com.riverlifeline.monitoring.TestData.AOI;
import static com.riverlifeline.monitoring.TestData.assessment;
import static com.riverlifeline.monitoring.TestData.catalog;
import static com.riverlifeline.monitoring.TestData.community;
import static com.riverlifeline.monitoring.TestData.pass;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riverlifeline.monitoring.TestData;
import com.riverlifeline.monitoring.application.exception.InvalidParameterException;
import com.riverlifeline.monitoring.application.view.Views.CommunityDetail;
import com.riverlifeline.monitoring.application.view.Views.Health;
import com.riverlifeline.monitoring.application.view.Views.LayerContent;
import com.riverlifeline.monitoring.application.view.Views.Meta;
import com.riverlifeline.monitoring.application.view.Views.PassCommunities;
import com.riverlifeline.monitoring.application.view.Views.PassEntry;
import com.riverlifeline.monitoring.application.view.Views.RiverLevel;
import com.riverlifeline.monitoring.application.view.Views.Summary;
import com.riverlifeline.monitoring.domain.exception.AoiNotFoundException;
import com.riverlifeline.monitoring.domain.exception.CatalogSchemaUnsupportedException;
import com.riverlifeline.monitoring.domain.exception.CatalogUnavailableException;
import com.riverlifeline.monitoring.domain.exception.CommunityNotFoundException;
import com.riverlifeline.monitoring.domain.exception.LayerNotFoundException;
import com.riverlifeline.monitoring.domain.exception.PassNotFoundException;
import com.riverlifeline.monitoring.domain.model.aoi.AoiId;
import com.riverlifeline.monitoring.domain.model.aoi.AoiParameters;
import com.riverlifeline.monitoring.domain.model.community.CommunityFilter;
import com.riverlifeline.monitoring.domain.model.community.CommunitySort;
import com.riverlifeline.monitoring.domain.model.pass.Pass;
import com.riverlifeline.monitoring.domain.model.pass.PassId;
import com.riverlifeline.monitoring.domain.model.pass.PassSelector;
import com.riverlifeline.monitoring.domain.model.pass.StatusCounts;
import com.riverlifeline.monitoring.domain.model.riverlevel.RiverLevelReading;
import com.riverlifeline.monitoring.domain.model.riverlevel.RiverLevelSeries;
import com.riverlifeline.monitoring.domain.model.status.Reason;
import com.riverlifeline.monitoring.domain.model.status.Status;
import com.riverlifeline.monitoring.domain.service.HeadlineLevel;
import com.riverlifeline.shared.domain.SchemaVersion;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QueryServicesTest {
    private final Pass july = pass("p-0704", "2026-07-04T21:40:11Z", true, new StatusCounts(2, 2, 0, 0, 0, 0));
    private final Pass october = pass("p-1015", "2026-10-15T21:40:33Z", true, new StatusCounts(2, 1, 0, 1, 0, 1));
    private final Pass partial = pass("p-1019", "2026-10-19T09:44:55Z", false);

    private final InMemoryRepositories repositories = new InMemoryRepositories()
            .withCatalog(catalog(july, october, partial))
            .withCommunities(
                    AOI,
                    community("osm-node-1", "Alvarães", true),
                    community("manual-caiambe", "Caiambé", true),
                    community("osm-node-9", "Agrovila", false))
            .withStatuses(
                    july,
                    assessment("osm-node-1", Status.CONNECTED, Reason.ROUTE_GE_60M, 400),
                    assessment("manual-caiambe", Status.CONNECTED, Reason.ROUTE_GE_60M, 100))
            .withStatuses(
                    october,
                    assessment("osm-node-1", Status.CONNECTED, Reason.ROUTE_GE_60M, 200),
                    assessment("manual-caiambe", Status.ISOLATED, Reason.NO_ROUTE_CONFIRMED, null))
            .withStatuses(partial, assessment("osm-node-1", Status.CONNECTED, Reason.ROUTE_GE_60M, 200))
            .withLayerFile("passes/p-1015/water.png");

    private final AoiQueryService aois = new AoiQueryService(repositories, repositories);
    private final PassQueryService passes = new PassQueryService(repositories, repositories);
    private final CommunityQueryService communities =
            new CommunityQueryService(repositories, repositories, repositories);

    // --- AOIs and summary ---------------------------------------------------------------------------------------

    @Test
    void anAoiIsDescribedWithItsPassCounts() {
        var overview = aois.getAoi("tefe");

        assertThat(overview.passCount()).isEqualTo(3);
        assertThat(overview.evaluatedPassCount()).isEqualTo(2);
        assertThat(overview.firstPassAt()).isEqualTo(july.acquiredAt());
        assertThat(overview.latestPassAt()).isEqualTo(partial.acquiredAt());
    }

    @Test
    void unknownOrMalformedAoisAreNotFound() {
        assertThatThrownBy(() -> aois.getAoi("coari")).isInstanceOf(AoiNotFoundException.class);
        assertThatThrownBy(() -> aois.getAoi("../etc")).isInstanceOf(AoiNotFoundException.class);
    }

    @Test
    void everyPublishedAreaIsListedWithoutAnyConfiguration() {
        repositories.withCatalog(catalog(TestData.aoi(AoiId.of("coari"), "Coari", TestData.PARAMETERS)));

        assertThat(aois.listAois()).extracting(aoi -> aoi.id().value()).containsExactly("coari", "tefe");
    }

    @Test
    void theSummaryIsForTheLatestEvaluatedPassOnOrBeforeTheDay() {
        Summary latest = aois.summary("tefe", null);
        assertThat(latest.pass()).isEqualTo(october);
        assertThat(latest.date()).isEqualTo(LocalDate.parse("2026-10-15"));
        assertThat(latest.headline().level()).isEqualTo(HeadlineLevel.CRITICAL);
        assertThat(latest.headline().text()).isEqualTo("1 community has lost the water route to Tefé.");

        Summary august = aois.summary("tefe", LocalDate.parse("2026-08-01"));
        assertThat(august.pass()).isEqualTo(july);
        assertThat(august.date()).isEqualTo(LocalDate.parse("2026-08-01"));

        Summary before = aois.summary("tefe", LocalDate.parse("2026-01-01"));
        assertThat(before.pass()).isNull();
        assertThat(before.headline().level()).isEqualTo(HeadlineLevel.INFO);
    }

    @Test
    void theCatalogVersionChangesWhenAPassIsPublished() {
        var before = aois.catalogVersion("tefe");
        repositories.withCatalog(catalog(july, october, partial, pass("p-1023", "2026-10-23T09:45:12Z", true)));
        var after = aois.catalogVersion("tefe");

        assertThat(before.etag()).matches("\"[0-9a-f]{8}\"");
        assertThat(after.etag()).isNotEqualTo(before.etag());
        assertThat(after.latestEvaluatedPassId()).isEqualTo(PassId.of("p-1023"));
        assertThat(before.latestPassId()).isEqualTo(partial.id());
        assertThat(before.latestEvaluatedPassId()).isEqualTo(october.id());
    }

    @Test
    void theRiverLevelIsFilteredAndCarriesTheAreasThreshold() {
        repositories.withRiverLevel(
                AOI,
                new RiverLevelSeries(
                        "ANA",
                        "12900001",
                        "Tefé-Missões",
                        "m",
                        List.of(
                                new RiverLevelReading(LocalDate.parse("2026-07-01"), 18.7),
                                new RiverLevelReading(LocalDate.parse("2026-10-23"), 8.0))));

        RiverLevel level = aois.riverLevel("tefe", LocalDate.parse("2026-10-01"), null);

        assertThat(level.readings()).extracting(RiverLevelReading::levelM).containsExactly(8.0);
        assertThat(level.lowWaterThresholdM()).isEqualTo(12.0);
        assertThatThrownBy(() -> aois.riverLevel("tefe", LocalDate.parse("2026-11-01"), LocalDate.parse("2026-10-01")))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    void withoutARiverLevelFileTheGaugeComesFromTheCatalog() {
        RiverLevel level = aois.riverLevel("tefe", null, null);

        assertThat(level.stationCode()).isEqualTo("12900001");
        assertThat(level.readings()).isEmpty();
    }

    // --- passes ---------------------------------------------------------------------------------------------------

    @Test
    void passesAreListedWithTheirNeighboursInTheFullList() {
        List<PassEntry> evaluated = passes.listPasses("tefe", true, null, null);

        assertThat(evaluated).extracting(PassEntry::pass).containsExactly(july, october);
        assertThat(evaluated.get(1).nextPassId())
                .as("the next pass, even if not evaluated")
                .isEqualTo(partial.id());
        assertThat(passes.listPasses("tefe", null, LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-15")))
                .extracting(PassEntry::pass)
                .containsExactly(october);
        assertThatThrownBy(() ->
                        passes.listPasses("tefe", null, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-01-01")))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    void latestResolvesToTheLatestEvaluatedPass() {
        assertThat(passes.getPass("tefe", PassSelector.LATEST).entry().pass()).isEqualTo(october);
        assertThatThrownBy(() -> passes.getPass("tefe", PassSelector.parse("p-9999")))
                .isInstanceOf(PassNotFoundException.class);
    }

    @Test
    void aLayerIsCachedForeverOnlyWhenTheClientNamedThePass() {
        LayerContent byId = passes.layer("tefe", PassSelector.parse("p-1015"), "water");
        LayerContent byAlias = passes.layer("tefe", PassSelector.LATEST, "water");

        assertThat(byId.immutable()).isTrue();
        assertThat(byAlias.immutable()).isFalse();
        assertThat(byAlias.passId()).isEqualTo(october.id());
    }

    @Test
    void missingLayersAreNotFound() {
        assertThatThrownBy(() -> passes.layer("tefe", PassSelector.LATEST, "flooded_vegetation"))
                .as("not in the catalog")
                .isInstanceOf(LayerNotFoundException.class);
        assertThatThrownBy(() -> passes.layer("tefe", PassSelector.LATEST, "radar_hh"))
                .as("in the catalog, missing on disk")
                .isInstanceOf(LayerNotFoundException.class);
        assertThatThrownBy(() -> passes.layer("tefe", PassSelector.LATEST, "sunset"))
                .isInstanceOf(LayerNotFoundException.class);
    }

    // --- communities ------------------------------------------------------------------------------------------

    @Test
    void onlyMonitoredCommunitiesAreListedWorstFirst() {
        PassCommunities list =
                communities.listOnPass("tefe", PassSelector.LATEST, CommunityFilter.ALL, CommunitySort.STATUS);

        assertThat(list.pass()).isEqualTo(october);
        assertThat(list.communities())
                .extracting(snapshot -> snapshot.community().name())
                .containsExactly("Caiambé", "Alvarães");
    }

    @Test
    void theListCanBeFiltered() {
        PassCommunities isolated = communities.listOnPass(
                "tefe",
                PassSelector.LATEST,
                new CommunityFilter(Set.of(Status.ISOLATED), false, null),
                CommunitySort.NAME);

        assertThat(isolated.communities()).hasSize(1);
        assertThat(isolated.communities().getFirst().assessment().status()).isEqualTo(Status.ISOLATED);
    }

    @Test
    void aPassThatWasNotEvaluatedShowsNoStatuses() {
        PassCommunities list =
                communities.listOnPass("tefe", PassSelector.parse("p-1019"), CommunityFilter.ALL, CommunitySort.STATUS);

        assertThat(list.communities())
                .allSatisfy(
                        snapshot -> assertThat(snapshot.assessment().status()).isEqualTo(Status.NO_DATA));
    }

    @Test
    void aCommunityCardHasTheSelectedPassAndTheEvaluatedHistory() {
        CommunityDetail card = communities.getCommunity("tefe", "manual-caiambe", PassSelector.LATEST);

        assertThat(card.current().pass()).isEqualTo(october);
        assertThat(card.current().assessment().status()).isEqualTo(Status.ISOLATED);
        assertThat(card.history()).extracting(entry -> entry.pass().id()).containsExactly(july.id(), october.id());

        CommunityDetail inJuly = communities.getCommunity("tefe", "manual-caiambe", PassSelector.parse("p-0704"));
        assertThat(inJuly.current().assessment().status()).isEqualTo(Status.CONNECTED);
    }

    @Test
    void anUnmonitoredCommunityHasNoStatus() {
        CommunityDetail card = communities.getCommunity("tefe", "osm-node-9", PassSelector.LATEST);

        assertThat(card.current()).isNull();
        assertThat(card.history()).isEmpty();
    }

    @Test
    void unknownCommunitiesAndPassesAreNotFound() {
        assertThatThrownBy(() -> communities.getCommunity("tefe", "osm-node-404", PassSelector.LATEST))
                .isInstanceOf(CommunityNotFoundException.class);
        assertThatThrownBy(() -> communities.getCommunity("tefe", "Not An Id", PassSelector.LATEST))
                .isInstanceOf(CommunityNotFoundException.class);
        assertThatThrownBy(() -> communities.getCommunity("tefe", "manual-caiambe", PassSelector.parse("p-404")))
                .isInstanceOf(PassNotFoundException.class);
    }

    @Test
    void theSafeWidthOfEachAreaDecidesWhatIsConnected() {
        AoiParameters strict = new AoiParameters(List.of(40, 100, 200, 400), 300, 40, 0.8, 12, -16, -14, -40, 1000);
        repositories.withCatalog(catalog(TestData.aoi(AOI, "Tefé", strict), july, october, partial));

        PassCommunities list =
                communities.listOnPass("tefe", PassSelector.LATEST, CommunityFilter.ALL, CommunitySort.STATUS);

        assertThat(list.communities())
                .filteredOn(snapshot -> snapshot.community().name().equals("Alvarães"))
                .singleElement()
                .satisfies(
                        snapshot -> assertThat(snapshot.assessment().status()).isEqualTo(Status.AT_RISK));
    }

    // --- health and meta ----------------------------------------------------------------------------------------

    @Test
    void withoutAConfiguredDefaultTheFirstAreaIsDescribed() {
        repositories.withCatalog(catalog(TestData.aoi(AoiId.of("coari"), "Coari", TestData.PARAMETERS)));

        Meta meta = new SystemQueryService(repositories, Optional.empty()).meta();
        Meta tefe = new SystemQueryService(repositories, Optional.of(AOI)).meta();

        assertThat(meta.aoiId()).isEqualTo(AoiId.of("coari"));
        assertThat(tefe.aoiId()).isEqualTo(AOI);
        assertThat(tefe.reasons()).hasSize(Reason.values().length);
        assertThat(tefe.reasons().getFirst().label()).isEqualTo("Route ≥ 60 m");
        assertThat(tefe.statusTable()).hasSize(4);
    }

    @Test
    void healthIsOkWhenTheCatalogLoads() {
        Health health = new SystemQueryService(repositories, Optional.of(AOI)).health();

        assertThat(health.ok()).isTrue();
        assertThat(health.catalog().schemaVersion()).isEqualTo("1.0.0");
    }

    @Test
    void healthIsDegradedButNeverFailsWhenTheCatalogCantBeRead() {
        repositories.failing(AOI, new CatalogSchemaUnsupportedException(AOI, SchemaVersion.parse("2.0.0"), 1));
        Health unsupported = new SystemQueryService(repositories, Optional.of(AOI)).health();
        assertThat(unsupported.ok()).isFalse();
        assertThat(unsupported.catalog().loaded()).isFalse();
        assertThat(unsupported.catalog().schemaVersion()).isEqualTo("2.0.0");

        repositories.failing(AOI, new CatalogUnavailableException(AOI, "missing"));
        assertThat(new SystemQueryService(repositories, Optional.of(AOI))
                        .health()
                        .ok())
                .isFalse();
    }

    @Test
    void withNoAreaPublishedHealthIsDegradedAndMetaUnavailable() {
        InMemoryRepositories empty = new InMemoryRepositories();
        SystemQueryService service = new SystemQueryService(empty, Optional.empty());

        assertThat(service.health().ok()).isFalse();
        assertThatThrownBy(service::meta).isInstanceOf(CatalogUnavailableException.class);
    }
}
