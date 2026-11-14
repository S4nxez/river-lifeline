package com.nisarsar.config;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nisarsar.dao.model.SiteCatalog;
import com.nisarsar.domain.services.JwtService;
import com.nisarsar.domain.services.SiteCatalogService;
import com.nisarsar.ui.SiteController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.test.web.servlet.MockMvc;

/** The security rules over the real web layer, without a database: the sites API is public, the rest is not. */
@WebMvcTest(SiteController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {
    @Autowired
    private MockMvc mvc;

    @MockBean
    private SiteCatalogService catalog;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private AuthenticationProvider authenticationProvider;

    @Test
    void theSitesApiIsPublic() throws Exception {
        when(catalog.getCatalog()).thenReturn(new SiteCatalog(List.of(), List.of()));

        mvc.perform(get("/api/sites")).andExpect(status().isOk());
    }

    @Test
    void everyOtherEndpointRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/anything-else")).andExpect(status().isUnauthorized());
    }
}
