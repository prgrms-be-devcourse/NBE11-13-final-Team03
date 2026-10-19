package com.team3.backend.search.controller;

import com.team3.backend.opportunity.domain.entity.ConditionType;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityCondition;
import com.team3.backend.opportunity.domain.entity.OpportunityRegion;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.entity.RegionRole;
import com.team3.backend.opportunity.domain.entity.RequirementType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.domain.entity.Region;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpportunitySearchPostgresTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("yon_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private RegionDirectory regionDirectory;

    @Test
    void regionFilterUsesPostgresJsonAndExactPageCounts() throws Exception {
        Region province = Region.builder().regionCode("1100000000")
                .regionName("Seoul").build();
        entityManager.persist(province);
        Region district = Region.builder().regionCode("1168000000")
                .regionName("Gangnam").parent(province).build();
        entityManager.persist(district);
        Region otherProvince = Region.builder().regionCode("2600000000")
                .regionName("Busan").build();
        entityManager.persist(otherProvince);

        Opportunity national = opportunity("PG region national", OpportunityType.YOUTH_POLICY);
        Opportunity specific = opportunity("PG region specific", OpportunityType.YOUTH_POLICY);
        Opportunity provinceSpecific = opportunity("PG region province", OpportunityType.YOUTH_POLICY);
        Opportunity unknown = opportunity("PG region unknown", OpportunityType.YOUTH_POLICY);
        Opportunity training = opportunity("PG region training", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity elsewhereTraining = opportunity("PG region elsewhere training", OpportunityType.VOCATIONAL_TRAINING);
        national.updateSummary(national.getTitle(), "career", "job", null);
        specific.updateSummary(specific.getTitle(), "career", "job", null);
        provinceSpecific.updateSummary(provinceSpecific.getTitle(), "housing", "support", null);
        unknown.updateSummary(unknown.getTitle(), "career", "job", null);
        condition(national, "NATIONAL");
        condition(unknown, "UNKNOWN");
        condition(elsewhereTraining, "NATIONAL");
        entityManager.persist(OpportunityRegion.builder()
                .version(specific.getCurrentVersion()).region(district)
                .regionRole(RegionRole.RESIDENCE).build());
        entityManager.persist(OpportunityRegion.builder()
                .version(provinceSpecific.getCurrentVersion()).region(province)
                .regionRole(RegionRole.RESIDENCE).build());
        entityManager.persist(OpportunityRegion.builder()
                .version(training.getCurrentVersion()).region(district)
                .regionRole(RegionRole.IMPLEMENTATION).build());
        entityManager.persist(OpportunityRegion.builder()
                .version(elsewhereTraining.getCurrentVersion()).region(otherProvince)
                .regionRole(RegionRole.IMPLEMENTATION).build());
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();

        MvcResult firstPage = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG region")
                        .param("region", province.getRegionCode())
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andReturn();

        MvcResult secondPage = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG region")
                        .param("region", province.getRegionCode())
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andReturn();

        List<Long> pagedIds = new ArrayList<>(opportunityIds(firstPage));
        pagedIds.addAll(opportunityIds(secondPage));
        assertEquals(pagedIds.size(), new HashSet<>(pagedIds).size());
        assertEquals(Set.of(national.getId(), specific.getId(), provinceSpecific.getId(), training.getId()),
                new HashSet<>(pagedIds));
        assertFalse(pagedIds.contains(unknown.getId()));
        assertFalse(pagedIds.contains(elsewhereTraining.getId()));

        MvcResult districtResult = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG region")
                        .param("region", district.getRegionCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andReturn();
        assertEquals(Set.of(national.getId(), specific.getId(), provinceSpecific.getId(), training.getId()),
                new HashSet<>(opportunityIds(districtResult)));

        MvcResult trainingResult = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG region")
                        .param("region", province.getRegionCode())
                        .param("type", "VOCATIONAL_TRAINING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(training.getId()), opportunityIds(trainingResult));

        MvcResult elsewhereResult = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG region")
                        .param("region", otherProvince.getRegionCode())
                        .param("type", "VOCATIONAL_TRAINING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(elsewhereTraining.getId()), opportunityIds(elsewhereResult));

        MvcResult combinedResult = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG region")
                        .param("region", district.getRegionCode())
                        .param("type", "YOUTH_POLICY")
                        .param("category", "career")
                        .param("subCategory", "job"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn();
        assertEquals(Set.of(national.getId(), specific.getId()),
                new HashSet<>(opportunityIds(combinedResult)));

        MvcResult parentResult = mockMvc.perform(get("/api/opportunities")
                        .param("keyword", "PG region")
                        .param("region", district.getRegionCode())
                        .param("type", "YOUTH_POLICY")
                        .param("category", "housing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertEquals(List.of(provinceSpecific.getId()), opportunityIds(parentResult));
    }

    private static List<Long> opportunityIds(MvcResult result) throws Exception {
        List<Number> ids = JsonPath.read(result.getResponse().getContentAsString(),
                "$.content[*].opportunityId");
        return ids.stream().map(Number::longValue).toList();
    }

    private Opportunity opportunity(String title, OpportunityType type) {
        Opportunity opportunity = Opportunity.builder().title(title)
                .opportunityType(type).build();
        entityManager.persist(opportunity);
        OpportunityVersion version = OpportunityVersion.builder()
                .opportunity(opportunity).versionNo(1)
                .normalizedSnapshot(Map.of("title", title))
                .personalizationStatus(PersonalizationStatus.UNKNOWN)
                .normalizationVersion("1").build();
        entityManager.persist(version);
        opportunity.changeCurrentVersion(version);
        return opportunity;
    }

    private void condition(Opportunity opportunity, String regionStatus) {
        entityManager.persist(OpportunityCondition.builder()
                .version(opportunity.getCurrentVersion())
                .conditionType(ConditionType.REGION)
                .requirementType(RequirementType.REQUIRED)
                .conditionField("residenceRegion")
                .expression(Map.of("status", regionStatus, "role", "RESIDENCE"))
                .validationStatus(ValidationStatus.VALID)
                .build());
    }
}
