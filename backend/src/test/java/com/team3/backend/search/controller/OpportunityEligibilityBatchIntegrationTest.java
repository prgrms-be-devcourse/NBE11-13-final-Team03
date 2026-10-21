package com.team3.backend.search.controller;

import com.jayway.jsonpath.JsonPath;
import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.domain.repository.EligibilityResultRepository;
import com.team3.backend.eligibility.service.EligibilityBatchRefresher;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.repository.OpportunitySourceRepository;
import com.team3.backend.opportunity.dto.*;
import com.team3.backend.opportunity.service.TrainingCourseNormalizationService;
import com.team3.backend.opportunity.service.YouthPolicyNormalizationService;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 실제 PostgreSQL에서 정규화·배치 최신화와 검색·상세 표시의 연결을 검증한다. */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduling.enabled=true",
        "collection.schedule.enabled=false",
        "yon.eligibility.batch.initial-delay-ms=86400000"
})
@AutoConfigureMockMvc
@Testcontainers
class OpportunityEligibilityBatchIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Long POLICY_SOURCE = 81001L;
    private static final Long TRAINING_SOURCE = 81002L;

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("yon_batch_search_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RegionDirectory regionDirectory;
    @Autowired private YouthPolicyNormalizationService policyNormalization;
    @Autowired private TrainingCourseNormalizationService trainingNormalization;
    @Autowired private OpportunitySourceRepository sourceRepository;
    @Autowired private EligibilityBatchRefresher batchRefresher;
    @Autowired private EligibilityResultRepository resultRepository;
    @MockitoSpyBean private EligibilityResultService resultService;

    /** 신규·변경 공고, 부분 실패와 재시도에서 저장된 최신 결과만 두 API에 표시되는지 확인한다. */
    @Test
    void normalizedOpportunitiesAppearOnlyAfterSuccessfulBatch() throws Exception {
        LocalDate today = LocalDate.now(KST);
        OffsetDateTime now = OffsetDateTime.now(KST);
        Long[] users = createUsers();
        Long ownerId = users[0];
        Long failedUserId = users[1];
        Long noProfileUserId = users[2];

        NormalizationResult first = normalizePolicy("BATCH-1", "통합 배치 정책 A", "34", null, today);
        NormalizationResult second = normalizePolicy("BATCH-2", "통합 배치 정책 B", "34",
                "추가 참여 요건은 원문 확인", today);
        NormalizationResult third = normalizePolicy("BATCH-3", "통합 배치 정책 C", "34", null, today);
        Long trainingId = normalizeTraining(today);
        Long unknownId = createIncompleteNormalizedOpportunity();
        save(ownerId, first.opportunityId());

        assertThat(item(first.opportunityId(), ownerId).get("eligibilityStatus")).isNull();
        assertThat(detail(first.opportunityId(), ownerId).get("eligibility")).isNull();
        assertThat(item(first.opportunityId(), null).get("eligibilityStatus")).isNull();
        assertThat(detail(first.opportunityId(), null).get("eligibility")).isNull();
        assertThat(item(first.opportunityId(), noProfileUserId).get("eligibilityStatus")).isNull();
        assertThat(detail(first.opportunityId(), noProfileUserId).get("eligibility")).isNull();
        assertThat(filtered(ownerId, "ELIGIBLE", 0, 10).get("totalElements")).isEqualTo(0);

        // 한 사용자의 실패는 다른 사용자의 트랜잭션을 막지 않고, 실패 사용자는 미판정으로 남는다.
        doThrow(new IllegalStateException("재현용 사용자 처리 실패"))
                .doCallRealMethod().when(resultService).refreshStale(eq(failedUserId), any(OffsetDateTime.class));
        assertThat(batchRefresher.refreshAllUsers(now)).isEqualTo(5);
        assertThat(item(first.opportunityId(), failedUserId).get("eligibilityStatus")).isNull();
        assertThat(detail(first.opportunityId(), failedUserId).get("eligibility")).isNull();
        assertThat(filtered(failedUserId, "ELIGIBLE", 0, 10).get("totalElements")).isEqualTo(0);

        Map<String, Object> firstItem = item(first.opportunityId(), ownerId);
        assertThat(firstItem.get("eligibilityStatus")).isEqualTo("ELIGIBLE");
        assertThat(firstItem.get("saved")).isEqualTo(true);
        Map<String, Object> firstDetail = detail(first.opportunityId(), ownerId);
        Map<?, ?> eligibility = (Map<?, ?>) firstDetail.get("eligibility");
        assertThat(eligibility.get("status")).isEqualTo("ELIGIBLE");
        assertThat((String) eligibility.get("reason")).isNotBlank();
        List<?> conditionResults = (List<?>) eligibility.get("conditions");
        assertThat(conditionResults).isNotEmpty();
        assertThat((String) ((Map<?, ?>) conditionResults.getFirst()).get("reason")).isNotBlank();
        assertThat(item(unknownId, ownerId).get("eligibilityStatus")).isEqualTo("UNKNOWN");
        assertThat(((Map<?, ?>) detail(unknownId, ownerId).get("eligibility")).get("status"))
                .isEqualTo("UNKNOWN");
        assertThat(item(second.opportunityId(), ownerId).get("eligibilityStatus"))
                .isEqualTo("POSSIBLY_ELIGIBLE");
        assertThat(item(trainingId, ownerId).get("eligibilityStatus")).isNotNull();
        assertThat(detail(trainingId, ownerId).get("eligibility")).isNotNull();
        assertThat(item(first.opportunityId(), null).get("saved")).isEqualTo(false);
        assertThat(item(first.opportunityId(), noProfileUserId).get("eligibilityStatus")).isNull();
        assertThat(detail(first.opportunityId(), noProfileUserId).get("eligibility")).isNull();
        assertThat(filtered(noProfileUserId, "ELIGIBLE", 0, 10).get("totalElements")).isEqualTo(0);

        Map<String, Object> eligiblePage0 = filtered(ownerId, "ELIGIBLE,POSSIBLY_ELIGIBLE", 0, 1);
        Map<String, Object> eligiblePage1 = filtered(ownerId, "ELIGIBLE,POSSIBLY_ELIGIBLE", 1, 1);
        Map<String, Object> eligiblePage2 = filtered(ownerId, "ELIGIBLE,POSSIBLY_ELIGIBLE", 2, 1);
        assertThat(((Number) eligiblePage0.get("totalElements")).longValue()).isEqualTo(3);
        assertThat(((Number) eligiblePage1.get("totalElements")).longValue()).isEqualTo(3);
        assertThat(((Number) eligiblePage2.get("totalElements")).longValue()).isEqualTo(3);
        assertThat(eligiblePage0.get("hasNext")).isEqualTo(true);
        assertThat(eligiblePage1.get("hasNext")).isEqualTo(true);
        assertThat(eligiblePage2.get("hasNext")).isEqualTo(false);
        assertThat(((Number) ((Map<?, ?>) ((List<?>) eligiblePage0.get("content")).getFirst())
                .get("opportunityId")).longValue()).isEqualTo(third.opportunityId());
        assertThat(((Number) ((Map<?, ?>) ((List<?>) eligiblePage1.get("content")).getFirst())
                .get("opportunityId")).longValue()).isEqualTo(second.opportunityId());
        assertThat(((Number) ((Map<?, ?>) ((List<?>) eligiblePage2.get("content")).getFirst())
                .get("opportunityId")).longValue()).isEqualTo(first.opportunityId());
        assertThat(filtered(ownerId, "ELIGIBLE", 0, 10).get("totalElements")).isEqualTo(2);
        assertThat(filtered(ownerId, "POSSIBLY_ELIGIBLE", 0, 10).get("totalElements")).isEqualTo(1);
        List<?> eligibleContent = (List<?>) filtered(ownerId, "ELIGIBLE", 0, 10).get("content");
        assertThat(eligibleContent.stream().map(value -> ((Number) ((Map<?, ?>) value).get("opportunityId"))
                .longValue()).toList()).containsExactlyInAnyOrder(first.opportunityId(), third.opportunityId());

        // 재시도는 실패 사용자만 판정하고, 이미 최신인 사용자는 다시 판정하지 않는다.
        assertThat(batchRefresher.refreshAllUsers(now)).isEqualTo(5);
        assertThat(item(first.opportunityId(), failedUserId).get("eligibilityStatus")).isEqualTo("ELIGIBLE");
        assertThat(item(first.opportunityId(), failedUserId).get("saved")).isEqualTo(false);
        assertThat(resultRepository.findByUserIdAndOpportunityIdIn(noProfileUserId,
                List.of(first.opportunityId(), second.opportunityId(), third.opportunityId()))).isEmpty();
        assertThat(batchRefresher.refreshAllUsers(now)).isZero();

        NormalizationResult changed = normalizePolicy("BATCH-1", "통합 배치 정책 A", "20", null, today);
        assertThat(changed.newVersion()).isTrue();
        assertThat(changed.versionId()).isNotEqualTo(first.versionId());
        assertThat(item(first.opportunityId(), ownerId).get("eligibilityStatus")).isNull();
        assertThat(detail(first.opportunityId(), ownerId).get("eligibility")).isNull();
        assertThat(filtered(ownerId, "ELIGIBLE", 0, 10).get("totalElements")).isEqualTo(1);
        assertThat(batchRefresher.refreshAllUsers(now)).isEqualTo(2);
        assertThat(item(first.opportunityId(), ownerId).get("eligibilityStatus")).isEqualTo("NOT_ELIGIBLE");
        assertThat(((Map<?, ?>) detail(first.opportunityId(), ownerId).get("eligibility")).get("status"))
                .isEqualTo("NOT_ELIGIBLE");
        assertThat(resultRepository.findByUserIdAndOpportunityIdIn(ownerId, List.of(first.opportunityId())))
                .singleElement().satisfies(result -> assertThat(result.getOpportunityVersionId())
                        .isEqualTo(changed.versionId()));
        assertThat(batchRefresher.refreshAllUsers(now)).isZero();
    }

    private Long[] createUsers() {
        Long[] ids = new Long[3];
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            Region seoul = Region.builder().regionCode("1100000000").regionName("서울").build();
            Region gangnam = Region.builder().regionCode("1168000000").regionName("강남")
                    .parent(seoul).build();
            entityManager.persist(seoul);
            entityManager.persist(gangnam);
            for (int index = 0; index < ids.length; index++) {
                User account = User.builder().kakaoId(990100L + index).role(Role.USER).build();
                entityManager.persist(account);
                ids[index] = account.getId();
                if (index < 2) {
                    entityManager.persist(EligibilityProfile.builder().userId(account.getId())
                            .birthDate(LocalDate.of(1998, 5, 15)).residenceRegion(gangnam)
                            .employmentStatus(EmploymentStatus.UNEMPLOYED)
                            .educationLevel(EducationLevel.UNIVERSITY)
                            .enrollmentStatus(EnrollmentStatus.GRADUATED).build());
                }
            }
        });
        regionDirectory.refresh();
        return ids;
    }

    private NormalizationResult normalizePolicy(String externalId, String title, String maxAge,
                                                String additionalQualification, LocalDate today) {
        YouthPolicyRaw raw = YouthPolicyRaw.builder().plcyNo(externalId).plcyNm(title)
                .lclsfNm("교육").mclsfNm("취업")
                .sprtTrgtMinAge("19").sprtTrgtMaxAge(maxAge).sprtTrgtAgeLmtYn("Y")
                .addAplyQlfcCndCn(additionalQualification)
                .aplyPrdSeCd("0057001")
                .aplyYmd(today.minusDays(5).format(DateTimeFormatter.BASIC_ISO_DATE) + " ~ "
                        + today.plusDays(20).format(DateTimeFormatter.BASIC_ISO_DATE))
                .zipCd("11680").build();
        return policyNormalization.normalize(new NormalizationCommand(POLICY_SOURCE, raw, List.of(),
                "https://example.org/" + externalId, LocalDateTime.now()));
    }

    private Long normalizeTraining(LocalDate today) {
        TrainingCourseRaw raw = TrainingCourseRaw.builder().trprId("BATCH-COURSE")
                .trprDegr("1").title("통합 배치 훈련").subTitle("훈련기관")
                .traStartDate(today.plusDays(10).toString())
                .traEndDate(today.plusDays(60).toString())
                .trainTarget("K-디지털 트레이닝").trainTargetCd("C0104")
                .trngAreaCd("11680").build();
        assertThat(trainingNormalization.normalizePage(new TrainingNormalizationCommand(TRAINING_SOURCE,
                List.of(raw), LocalDateTime.now())).created()).isEqualTo(1);
        return sourceRepository.findBySourceIdAndExternalId(TRAINING_SOURCE, "BATCH-COURSE:1")
                .orElseThrow().getOpportunity().getId();
    }

    private Long createIncompleteNormalizedOpportunity() {
        Long[] id = new Long[1];
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            Opportunity opportunity = Opportunity.builder().opportunityType(OpportunityType.YOUTH_POLICY)
                    .title("통합 배치 조건 미확인").category("기타").build();
            entityManager.persist(opportunity);
            OpportunityVersion version = OpportunityVersion.builder().opportunity(opportunity).versionNo(1)
                    .normalizationVersion("test").normalizedSnapshot(Map.of("title", opportunity.getTitle()))
                    .personalizationStatus(PersonalizationStatus.UNKNOWN).build();
            entityManager.persist(version);
            opportunity.changeCurrentVersion(version);
            id[0] = opportunity.getId();
        });
        return id[0];
    }

    private void save(Long userId, Long opportunityId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            UserOpportunityPreference preference = UserOpportunityPreference.builder()
                    .user(entityManager.getReference(User.class, userId))
                    .opportunity(entityManager.getReference(Opportunity.class, opportunityId)).build();
            preference.markSaved();
            entityManager.persist(preference);
        });
    }

    private Map<String, Object> item(Long opportunityId, Long userId) throws Exception {
        var request = get("/api/opportunities").param("keyword", "통합 배치");
        if (userId != null) {
            request.with(user(new CustomUserDetails(userId, Role.USER, "batch-integration")));
        }
        var response = mockMvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        List<Map<String, Object>> content = JsonPath.read(response.getContentAsString(), "$.content");
        return content.stream().filter(row -> ((Number) row.get("opportunityId")).longValue() == opportunityId)
                .findFirst().orElseThrow();
    }

    private Map<String, Object> detail(Long opportunityId, Long userId) throws Exception {
        var request = get("/api/opportunities/{id}", opportunityId);
        if (userId != null) {
            request.with(user(new CustomUserDetails(userId, Role.USER, "batch-integration")));
        }
        var response = mockMvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return JsonPath.parse(response.getContentAsString()).read("$");
    }

    private Map<String, Object> filtered(Long userId, String statuses, int page, int size) throws Exception {
        var response = mockMvc.perform(get("/api/opportunities")
                        .with(user(new CustomUserDetails(userId, Role.USER, "batch-integration")))
                        .param("keyword", "통합 배치").param("type", "YOUTH_POLICY")
                        .param("category", "교육").param("subCategory", "취업")
                        .param("applicationStatus", "OPEN")
                        .param("eligibilityStatuses", statuses)
                        .param("page", String.valueOf(page)).param("size", String.valueOf(size)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return JsonPath.parse(response.getContentAsString()).read("$");
    }
}
