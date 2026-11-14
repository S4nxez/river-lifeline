package com.riverlifeline.monitoring.infrastructure.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** A catalog of another major version: data endpoints answer 503, health stays 200 and says it is degraded. */
@SpringBootTest(properties = WithoutDatabase.PROPERTY)
@AutoConfigureMockMvc
class ApiWithoutUsableCatalogTest {
    @TempDir
    static Path root;

    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void writeCatalogs() throws IOException {
        Path future = Files.createDirectories(root.resolve("aois/future"));
        Files.writeString(future.resolve("catalog.json"), "{\"schema_version\": \"2.0.0\"}");
        Path broken = Files.createDirectories(root.resolve("aois/broken"));
        Files.writeString(broken.resolve("catalog.json"), "not json");
    }

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("river.data-dir", () -> root.toString());
        registry.add("river.default-aoi-id", () -> "future");
    }

    @Test
    void aCatalogOfAnotherMajorIsRefused() throws Exception {
        mvc.perform(get("/api/v1/aois/future/summary"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("catalog_schema_unsupported"));
        mvc.perform(get("/api/v1/meta"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("catalog_schema_unsupported"));
    }

    @Test
    void anUnreadableCatalogIsUnavailable() throws Exception {
        mvc.perform(get("/api/v1/aois/broken/passes"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("catalog_unavailable"));
    }

    @Test
    void healthIsAlways200() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("degraded"))
                .andExpect(jsonPath("$.catalog.loaded").value(false))
                .andExpect(jsonPath("$.catalog.schema_version").value("2.0.0"));
    }
}
