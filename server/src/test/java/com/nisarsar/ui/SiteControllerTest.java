package com.nisarsar.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.nisarsar.dao.model.SarSite;
import com.nisarsar.dao.model.SiteCatalog;
import com.nisarsar.dao.model.SiteObservationSeries;
import com.nisarsar.domain.services.SiteCatalogService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class SiteControllerTest {
    private static final List<String> DATES = List.of("2024-01-01", "2024-02-01");
    private static final SarSite VALENCIA = site("ES_VALENCIA_001", List.of(-10.0, -11.0), List.of(-17.0, -18.0));
    private static final SarSite DONANA = site("ES_DONANA_001", List.of(-12.0, -13.0), List.of(-19.0, -20.0));
    private static final SiteCatalog CATALOG = new SiteCatalog(DATES, List.of(VALENCIA, DONANA));

    @Mock
    private SiteCatalogService catalogService;

    @InjectMocks
    private SiteController controller;

    @BeforeEach
    void serveTheCatalog() {
        when(catalogService.getCatalog()).thenReturn(CATALOG);
    }

    @Test
    void getSitesReturnsTheWholeCatalog() {
        assertThat(controller.getSites()).isEqualTo(CATALOG);
    }

    @Test
    void getSiteReturnsTheSiteWithThatId() {
        assertThat(controller.getSite("ES_DONANA_001")).isEqualTo(DONANA);
    }

    @Test
    void getSiteAnswers404ForAnUnknownId() {
        assertThatThrownBy(() -> controller.getSite("NOPE"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).contains("NOPE");
                });
    }

    @Test
    void getObservationsReturnsTheSiteSeriesOverTheCatalogDates() {
        assertThat(controller.getObservations("ES_VALENCIA_001"))
                .isEqualTo(new SiteObservationSeries(
                        null, "ES_VALENCIA_001", DATES, List.of(-10.0, -11.0), List.of(-17.0, -18.0)));
    }

    private static SarSite site(String id, List<Double> vv, List<Double> vh) {
        return new SarSite(id, "Name", "Spain", 39.3, -0.35, "high", "INCREASE", "FLOOD", "Description", 0.9, vv, vh);
    }
}
