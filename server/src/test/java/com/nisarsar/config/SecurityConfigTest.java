package com.nisarsar.config;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nisarsar.dao.model.Connectivity.Overview;
import com.nisarsar.domain.services.ConnectivityService;
import com.nisarsar.domain.services.JwtService;
import com.nisarsar.ui.ConnectivityController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.test.web.servlet.MockMvc;

/** The security rules over the real web layer, without a database: the monitor API is public, the rest is not. */
@WebMvcTest(ConnectivityController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {
    @Autowired
    private MockMvc mvc;

    @MockBean
    private ConnectivityService connectivity;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private AuthenticationProvider authenticationProvider;

    @Test
    void theMonitorApiIsPublic() throws Exception {
        when(connectivity.overview()).thenReturn(new Overview(null, null, List.of()));
        when(connectivity.passes()).thenReturn(List.of());

        mvc.perform(get("/api/communities")).andExpect(status().isOk());
        mvc.perform(get("/api/passes")).andExpect(status().isOk());
    }

    @Test
    void everyOtherEndpointRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/anything-else")).andExpect(status().isUnauthorized());
    }
}
