package com.team3.backend.collection.controller;

import com.team3.backend.client.DataServiceClient;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 수집 관리자 API가 권한을 확인하고 DataService 응답을 전달하는지, 설정 오류는 503으로 바꾸는지 확인한다 (DataService는 목) */
@ActiveProfiles("test")
@SpringBootTest(properties = "app.scheduling.enabled=false")
@AutoConfigureMockMvc
class CollectionAdminControllerTest {

    private static final Request REQUEST = Request.create(Request.HttpMethod.GET, "http://data-service/internal",
            Map.of(), null, StandardCharsets.UTF_8, null);

    @Autowired private MockMvc mvc;
    @MockitoBean private DataServiceClient dataService;

    @Test
    @WithMockUser(authorities = "ADMIN")
    void startSyncRelaysStatusBodyAndRewritesLocation() throws Exception {
        when(dataService.startSync("YOUTH_POLICY", "50", null)).thenReturn(response(202,
                "{\"jobId\":7,\"status\":\"PENDING\"}",
                Map.of("Content-Type", List.of("application/json"),
                        "Location", List.of("/internal/collection/syncs/7"))));

        mvc.perform(post("/api/admin/collection/syncs/YOUTH_POLICY").with(csrf()).param("pageSize", "50"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/admin/collection/syncs/7"))
                .andExpect(jsonPath("$.jobId").value(7))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void relaysDataServiceErrorsAsIs() throws Exception {
        when(dataService.getSync("999999")).thenReturn(response(404,
                "{\"code\":\"COLLECTION_004\",\"message\":\"수집 실행·출처 또는 원본 레코드를 찾을 수 없습니다.\",\"fieldErrors\":{}}",
                Map.of("Content-Type", List.of("application/json"))));
        when(dataService.listSyncs(null)).thenReturn(response(400,
                "{\"code\":\"COLLECTION_003\",\"message\":\"수집 요청 값을 확인하세요.\",\"fieldErrors\":{}}",
                Map.of("Content-Type", List.of("application/json"))));

        mvc.perform(get("/api/admin/collection/syncs/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COLLECTION_004"));
        mvc.perform(get("/api/admin/collection/syncs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COLLECTION_003"));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void rawRecordBodyIsPassedThrough() throws Exception {
        String json = "{\"syncId\":1,\"externalId\":\"p1\",\"payload\":{\"a\":1},\"collectedAt\":\"2026-10-23T09:00:00+09:00\"}";
        when(dataService.saveRawRecord(json)).thenReturn(response(200, "{\"id\":3}",
                Map.of("Content-Type", List.of("application/json"))));

        mvc.perform(post("/api/admin/collection/raw-records").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"id\":3}"));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void dataServiceUnreachableIsServiceUnavailable() throws Exception {
        when(dataService.getRawRecord("1")).thenThrow(FeignException.class);

        mvc.perform(get("/api/admin/collection/raw-records/1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COLLECTION_008"));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void internalKeyRejectionIsServiceUnavailableNotAuthError() throws Exception {
        when(dataService.getSync("1")).thenReturn(response(401,
                "{\"code\":\"AUTH_008\",\"message\":\"유효하지 않은 내부 API Key입니다.\",\"fieldErrors\":{}}",
                Map.of("Content-Type", List.of("application/json"))));
        when(dataService.listSyncs(null)).thenReturn(response(403, "",
                Map.of("Content-Type", List.of("application/json"))));

        mvc.perform(get("/api/admin/collection/syncs/1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COLLECTION_008"));
        mvc.perform(get("/api/admin/collection/syncs"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COLLECTION_008"));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void notFoundWithoutCollectionCodeIsServiceUnavailable() throws Exception {
        when(dataService.getRawRecord("1")).thenReturn(response(404,
                "{\"timestamp\":\"2026-10-23T09:00:00\",\"status\":404,\"path\":\"/internal/collection/raw-records/1\"}",
                Map.of("Content-Type", List.of("application/json"))));

        mvc.perform(get("/api/admin/collection/raw-records/1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COLLECTION_008"));
    }

    @Test
    void configurationErrorRules() {
        byte[] notFound = "{\"code\":\"COLLECTION_004\",\"message\":\"없음\"}".getBytes(StandardCharsets.UTF_8);
        byte[] empty = new byte[0];
        assertThat(CollectionAdminController.isConfigurationError(401, notFound)).isTrue();
        assertThat(CollectionAdminController.isConfigurationError(403, empty)).isTrue();
        assertThat(CollectionAdminController.isConfigurationError(404, empty)).isTrue();
        assertThat(CollectionAdminController.isConfigurationError(404, notFound)).isFalse();
        assertThat(CollectionAdminController.isConfigurationError(400, empty)).isFalse();
        assertThat(CollectionAdminController.isConfigurationError(409, empty)).isFalse();
        assertThat(CollectionAdminController.isConfigurationError(503, empty)).isFalse();
    }

    @Test
    @WithMockUser(authorities = "USER")
    void memberCannotReachCollectionAdminApi() throws Exception {
        mvc.perform(get("/api/admin/collection/syncs/1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COLLECTION_007"));
        mvc.perform(post("/api/admin/collection/syncs/YOUTH_POLICY").with(csrf()))
                .andExpect(status().isForbidden());
        verify(dataService, never()).getSync(any());
        verify(dataService, never()).startSync(any(), any(), any());
    }

    @Test
    void adminLocationKeepsOtherLocations() {
        assertThat(CollectionAdminController.adminLocation("http://data-service:8082/internal/collection/syncs/5"))
                .hasToString("/api/admin/collection/syncs/5");
        assertThat(CollectionAdminController.adminLocation("/elsewhere/1")).hasToString("/elsewhere/1");
    }

    private static Response response(int status, String body, Map<String, Collection<String>> headers) {
        return Response.builder().status(status).reason("test").request(REQUEST).headers(headers)
                .body(body, StandardCharsets.UTF_8).build();
    }
}
