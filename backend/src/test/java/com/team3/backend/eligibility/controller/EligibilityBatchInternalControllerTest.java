package com.team3.backend.eligibility.controller;

import com.team3.backend.eligibility.service.EligibilityBatchRefresher;
import com.team3.backend.eligibility.service.EligibilityBatchRefresher.RunResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class EligibilityBatchInternalControllerTest {

    private static final String PATH = "/api/internal/eligibility/batch/refresh";

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private EligibilityBatchRefresher batchRefresher;

    @Test
    void 내부키가_없으면_401이고_실행하지_않는다() throws Exception {
        mockMvc.perform(post(PATH).servletPath(PATH))
                .andExpect(status().isUnauthorized());
        verify(batchRefresher, never()).tryRun(any());
    }

    @Test
    void 내부키로_즉시_실행하고_결과를_돌려준다() throws Exception {
        when(batchRefresher.tryRun(any())).thenReturn(Optional.of(new RunResult(4, 0, 24, 565)));

        mockMvc.perform(post(PATH).servletPath(PATH).header("X-INTERNAL-KEY", "test-internal-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.users").value(4))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.judged").value(24))
                .andExpect(jsonPath("$.elapsedMs").value(565));
    }

    @Test
    void 이미_실행_중이면_409() throws Exception {
        when(batchRefresher.tryRun(any())).thenReturn(Optional.empty());

        mockMvc.perform(post(PATH).servletPath(PATH).header("X-INTERNAL-KEY", "test-internal-api-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("ALREADY_RUNNING"));
    }

    @Test
    void 배치가_꺼져_있으면_503() {
        @SuppressWarnings("unchecked")
        ObjectProvider<EligibilityBatchRefresher> none = mock(ObjectProvider.class);
        var response = new EligibilityBatchInternalController(none).refresh();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().status()).isEqualTo("DISABLED");
    }
}
