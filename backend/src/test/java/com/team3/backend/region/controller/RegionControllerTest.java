package com.team3.backend.region.controller;

import com.team3.backend.region.dto.RegionResponse;
import com.team3.backend.region.dto.RegionsResponse;
import com.team3.backend.region.service.RegionQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class RegionControllerTest {

    private static final String REGIONS_URL = "/api/regions";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegionQueryService regionQueryService;

    @Test
    void 비로그인_사용자도_지역_목록을_조회한다() throws Exception {
        when(regionQueryService.getRegions()).thenReturn(new RegionsResponse(List.of(
                new RegionResponse("1100000000", "서울특별시", null, false),
                new RegionResponse("1168000000", "강남구", "1100000000", true),
                new RegionResponse("3611000000", "세종특별자치시", null, true))));

        mockMvc.perform(get(REGIONS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions.length()").value(3))
                .andExpect(jsonPath("$.regions[0].code").value("1100000000"))
                .andExpect(jsonPath("$.regions[0].parentCode").doesNotExist())
                .andExpect(jsonPath("$.regions[0].leaf").value(false))
                .andExpect(jsonPath("$.regions[1].parentCode").value("1100000000"))
                .andExpect(jsonPath("$.regions[1].leaf").value(true))
                .andExpect(jsonPath("$.regions[2].name").value("세종특별자치시"))
                .andExpect(jsonPath("$.regions[2].leaf").value(true));
    }

    @Test
    void 지역_사전이_비어_있으면_빈_배열() throws Exception {
        when(regionQueryService.getRegions()).thenReturn(new RegionsResponse(List.of()));

        mockMvc.perform(get(REGIONS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions").isArray())
                .andExpect(jsonPath("$.regions").isEmpty());
    }

    @Test
    void GET만_공개하고_다른_메서드는_로그인이_필요하다() throws Exception {
        mockMvc.perform(post(REGIONS_URL).with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
