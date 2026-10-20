package com.team3.backend.collection.controller;

import com.team3.backend.collection.service.SyncJobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduling.enabled=false",
        "collection.normalization.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
@AutoConfigureMockMvc
class CollectionOpenApiTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private SyncJobService jobs;

    @Test
    void documentsCollectionContractsWithoutAuthentication() throws Exception {
        var sync = "$.paths['/api/admin/collection/syncs/{source}'].post";
        var job = "$.paths['/api/admin/collection/syncs/{jobId}'].get";
        var save = "$.paths['/api/admin/collection/raw-records'].post";
        var raw = "$.paths['/api/admin/collection/raw-records/{rawRecordId}'].get";
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(sync + ".summary").value("수동 수집 최신화 시작"))
                .andExpect(jsonPath(sync + ".responses['202'].headers.Location").exists())
                .andExpect(jsonPath(sync + ".responses['202'].content['*/*'].schema['$ref']").value("#/components/schemas/SyncStartResponse"))
                .andExpect(jsonPath(sync + ".parameters[1].schema.type").value("integer"))
                .andExpect(jsonPath(sync + ".parameters[1].schema.default").value(100))
                .andExpect(jsonPath(sync + ".parameters[2].schema.maximum").value(1000))
                .andExpect(jsonPath(sync + ".responses['409']").exists())
                .andExpect(jsonPath(sync + ".responses['503']").exists())
                .andExpect(jsonPath(job + ".responses['200']").exists())
                .andExpect(jsonPath(job + ".responses['404']").exists())
                .andExpect(jsonPath(save + ".responses['200']").exists())
                .andExpect(jsonPath(save + ".responses['409']").exists())
                .andExpect(jsonPath(raw + ".responses['200']").exists())
                .andExpect(jsonPath(raw + ".responses['404']").exists())
                .andExpect(jsonPath("$.components.schemas.RawRecordSaveRequest.required").isArray())
                .andExpect(jsonPath("$.components.schemas.RawRecordSaveRequest.properties.payload.type").value("object"))
                .andExpect(jsonPath("$.components.schemas.RawRecordResponse.properties.payload.type").value("object"))
                .andExpect(jsonPath("$.components.schemas.RawRecordSaveRequest.properties.payloadHash.deprecated").value(true));
        for (var operation : new String[]{sync, job, save, raw}) {
            mvc.perform(get("/v3/api-docs"))
                    .andExpect(jsonPath(operation + ".security[0].cookieAuth").isArray())
                    .andExpect(jsonPath(operation + ".responses['401']").exists())
                    .andExpect(jsonPath(operation + ".responses['403']").exists());
        }
    }

    @Test
    void swaggerUiIsAccessibleWithoutAuthentication() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger UI")));
    }
}
