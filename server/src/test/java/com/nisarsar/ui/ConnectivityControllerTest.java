package com.nisarsar.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.nisarsar.dao.model.CommunityKind;
import com.nisarsar.dao.model.Connectivity.CommunityHistory;
import com.nisarsar.dao.model.Connectivity.Overview;
import com.nisarsar.dao.model.Connectivity.PassSummary;
import com.nisarsar.dao.model.Connectivity.Place;
import com.nisarsar.dao.model.Connectivity.StatusCounts;
import com.nisarsar.domain.services.ConnectivityService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ConnectivityControllerTest {
    @Mock
    private ConnectivityService service;

    @InjectMocks
    private ConnectivityController controller;

    @Test
    void servesTheOverview() {
        Overview overview = new Overview(new Place("tefe", "Tefé", CommunityKind.HUB, -3.35, -64.71), null, List.of());
        when(service.overview()).thenReturn(overview);

        assertThat(controller.overview()).isEqualTo(overview);
    }

    @Test
    void servesTheHistoryOfACommunity() {
        CommunityHistory history =
                new CommunityHistory("uarini", "Uarini", CommunityKind.TOWN, -2.99, -65.11, List.of());
        when(service.history("uarini")).thenReturn(Optional.of(history));

        assertThat(controller.community("uarini")).isEqualTo(history);
    }

    @Test
    void anUnknownCommunityIs404() {
        when(service.history("atlantis")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.community("atlantis"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).isEqualTo("Community not found: atlantis");
                });
    }

    @Test
    void servesThePasses() {
        List<PassSummary> passes =
                List.of(new PassSummary("p1", "NISAR", "2026-08-04", 1650, new StatusCounts(4, 0, 0, 0)));
        when(service.passes()).thenReturn(passes);

        assertThat(controller.passes()).isEqualTo(passes);
    }
}
