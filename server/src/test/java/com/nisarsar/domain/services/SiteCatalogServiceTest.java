package com.nisarsar.domain.services;

import static com.nisarsar.dao.model.entities.EntityFixtures.observation;
import static com.nisarsar.dao.model.entities.EntityFixtures.site;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.nisarsar.dao.model.SarSite;
import com.nisarsar.dao.model.SiteCatalog;
import com.nisarsar.dao.repositories.SarSiteRepository;
import com.nisarsar.dao.repositories.SiteObservationSeriesRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SiteCatalogServiceTest {
    @Mock
    private SiteObservationSeriesRepository observations;

    @Mock
    private SarSiteRepository sites;

    @InjectMocks
    private SiteCatalogService service;

    @Test
    void listsEveryObservationDateOnceInChronologicalOrder() {
        when(sites.findAll()).thenReturn(List.of(site("A", "HIGH"), site("B", "LOW")));
        when(observations.findAll())
                .thenReturn(List.of(
                        observation("A", "2024-03-01", "-10", "-17"),
                        observation("B", "2024-01-01", "-11", "-18"),
                        observation("A", "2024-01-01", "-12", "-19")));

        assertThat(service.getCatalog().observationDates()).containsExactly("2024-01-01", "2024-03-01");
    }

    @Test
    void alignsEachSiteSeriesWithTheSharedDatesLeavingGapsAsNull() {
        when(sites.findAll()).thenReturn(List.of(site("A", "HIGH"), site("B", "LOW")));
        when(observations.findAll())
                .thenReturn(List.of(
                        observation("A", "2024-01-01", "-10.5", "-17.5"),
                        observation("A", "2024-02-01", "-11.5", "-18.5"),
                        observation("A", "2024-03-01", "-12.5", "-19.5"),
                        observation("B", "2024-02-01", "-9.25", "-16.25")));

        List<SarSite> result = service.getCatalog().sites();

        assertThat(result.get(0).vv()).containsExactly(-10.5, -11.5, -12.5);
        assertThat(result.get(0).vh()).containsExactly(-17.5, -18.5, -19.5);
        assertThat(result.get(1).vv()).containsExactly(null, -9.25, null);
        assertThat(result.get(1).vh()).containsExactly(null, -16.25, null);
    }

    @Test
    void mapsTheSiteFieldsAndLowercasesTheChangeLevel() {
        when(sites.findAll()).thenReturn(List.of(site("ES_VALENCIA_001", "HIGH")));
        when(observations.findAll()).thenReturn(List.of());

        SarSite result = service.getCatalog().sites().get(0);

        assertThat(result.id()).isEqualTo("ES_VALENCIA_001");
        assertThat(result.name()).isEqualTo("Site ES_VALENCIA_001");
        assertThat(result.country()).isEqualTo("Spain");
        assertThat(result.lat()).isEqualTo(39.304);
        assertThat(result.lng()).isEqualTo(-0.355);
        assertThat(result.level()).isEqualTo("high");
        assertThat(result.change()).isEqualTo("INCREASE");
        assertThat(result.category()).isEqualTo("FLOOD");
        assertThat(result.description()).isEqualTo("Description of ES_VALENCIA_001");
        assertThat(result.confidence()).isEqualTo(0.965);
    }

    @Test
    void aSiteWithoutObservationsGetsOnlyGaps() {
        when(sites.findAll()).thenReturn(List.of(site("A", "HIGH"), site("B", "MEDIUM")));
        when(observations.findAll()).thenReturn(List.of(observation("A", "2024-01-01", "-10", "-17")));

        SarSite withoutObservations = service.getCatalog().sites().get(1);

        assertThat(withoutObservations.vv()).containsExactly((Double) null);
        assertThat(withoutObservations.vh()).containsExactly((Double) null);
    }

    @Test
    void anEmptyDatabaseGivesAnEmptyCatalog() {
        when(sites.findAll()).thenReturn(List.of());
        when(observations.findAll()).thenReturn(List.of());

        assertThat(service.getCatalog()).isEqualTo(new SiteCatalog(List.of(), List.of()));
    }
}
