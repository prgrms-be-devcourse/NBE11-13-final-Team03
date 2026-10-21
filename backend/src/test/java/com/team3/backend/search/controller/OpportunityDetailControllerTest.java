package com.team3.backend.search.controller;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.search.dto.OpportunityDetailResponseDto;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 공개 상세와 현재 버전·사용자별 표시 데이터의 경계를 검증한다. */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpportunityDetailControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private RegionDirectory regionDirectory;

    @AfterEach
    void clearRegionIndex() {
        regionDirectory.refresh();
    }

    @Test
    void anonymousPolicyShowsCurrentDetailsAllWindowsAndRegions() throws Exception {
        Opportunity policy = opportunity("정책 상세", OpportunityType.YOUTH_POLICY);
        OpportunityVersion previous = policy.getCurrentVersion();
        OpportunityVersion current = version(policy, 2);
        policy.changeCurrentVersion(current);
        entityManager.persist(YouthPolicyDetail.builder().version(previous).policyNo("OLD")
                .description("과거 설명").build());
        entityManager.persist(YouthPolicyDetail.builder().version(current).policyNo("P-1")
                .description("정책 설명").supportContent("지원 내용").keywords("주거,청년")
                .supervisingInstitutionName("주관 기관").operatingInstitutionName("운영 기관")
                .applicationMethod("온라인 신청").screeningMethod("서류 심사")
                .submissionDocuments("신분증").applicationUrl("https://example.org/apply")
                .referenceUrl1("https://example.org/reference").businessStartDate(LocalDate.of(2026, 1, 1))
                .businessPeriodText("연간 사업").supportScale("100").otherNotes("기타 사항").build());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        window(current, today.minusDays(2), today.plusDays(2), ValidationStatus.VALID);
        window(current, today.plusDays(10), today.plusDays(20), ValidationStatus.VALID);
        window(previous, today.minusDays(20), today.minusDays(10), ValidationStatus.VALID);
        Region seoul = region("1100000000", "서울");
        Region busan = region("2600000000", "부산");
        entityManager.persist(OpportunityRegion.builder().version(current).region(seoul)
                .regionRole(RegionRole.RESIDENCE).build());
        entityManager.persist(OpportunityRegion.builder().version(current).region(busan)
                .regionRole(RegionRole.RESIDENCE).build());
        condition(current, ConditionType.AGE, "age", Map.of("status", "RANGE", "min", 19, "max", 34),
                ValidationStatus.VALID);
        condition(current, ConditionType.INCOME, "income", Map.of("status", "UNKNOWN", "min", 999),
                ValidationStatus.NEEDS_REVIEW);
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();

        mockMvc.perform(get("/api/opportunities/{id}", policy.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opportunityId").value(policy.getId()))
                .andExpect(jsonPath("$.applicationStatus").value("OPEN"))
                .andExpect(jsonPath("$.applicationWindows.length()").value(2))
                .andExpect(jsonPath("$.regions.length()").value(2))
                .andExpect(jsonPath("$.conditions[0].min").value(19))
                .andExpect(jsonPath("$.conditions[1].min").doesNotExist())
                .andExpect(jsonPath("$.detail.policyNo").value("P-1"))
                .andExpect(jsonPath("$.detail.description").value("정책 설명"))
                .andExpect(jsonPath("$.detail.supportContent").value("지원 내용"))
                .andExpect(jsonPath("$.detail.applicationUrl").value("https://example.org/apply"))
                .andExpect(jsonPath("$.detail.businessStartDate").value("2026-01-01"))
                .andExpect(jsonPath("$.detail.businessPeriodText").value("연간 사업"))
                .andExpect(jsonPath("$.detailUrl").doesNotExist())
                .andExpect(jsonPath("$.eligibility").doesNotExist())
                .andExpect(jsonPath("$.saved").value(false));
    }

    @Test
    void trainingKeepsUnknownApplicationStatusAndMissingDetailIsSafe() throws Exception {
        Opportunity training = opportunity("훈련 상세", OpportunityType.VOCATIONAL_TRAINING);
        OpportunityVersion version = training.getCurrentVersion();
        training.updateSummary(training.getTitle(), "교육", "직업훈련", "https://example.org/course");
        entityManager.persist(TrainingCourseDetail.builder().version(version).courseId("T-1").courseRound(3)
                .institutionName("훈련기관").trainingStartDate(LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(5))
                .trainingEndDate(LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(50))
                .actualTrainingCost(500_000L).courseFee(100_000L).capacity(20).ncsCode("12345678")
                .trainingTargetName("국민내일배움카드").address("서울").telNo("02-1234-5678").build());
        entityManager.persist(ApplicationWindow.builder().version(version).periodType(PeriodType.UNKNOWN)
                .validationStatus(ValidationStatus.UNKNOWN).build());
        Region seoul = region("1100000000", "서울");
        entityManager.persist(OpportunityRegion.builder().version(version).region(seoul)
                .regionRole(RegionRole.IMPLEMENTATION).build());
        Opportunity missingDetail = opportunity("상세 누락", OpportunityType.VOCATIONAL_TRAINING);
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();

        mockMvc.perform(get("/api/opportunities/{id}", training.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.applicationWindows[0].endDate").doesNotExist())
                .andExpect(jsonPath("$.regions[0].role").value("IMPLEMENTATION"))
                .andExpect(jsonPath("$.regions[0].validationStatus").doesNotExist())
                .andExpect(jsonPath("$.detail.courseId").value("T-1"))
                .andExpect(jsonPath("$.detail.trainingStartDate").isNotEmpty())
                .andExpect(jsonPath("$.detail.courseFee").value(100_000));
        mockMvc.perform(get("/api/opportunities/{id}", missingDetail.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void unknownRegionIsNotShownAsNational() throws Exception {
        Opportunity unknown = opportunity("지역 미확인", OpportunityType.YOUTH_POLICY);
        Opportunity national = opportunity("전국 정책", OpportunityType.YOUTH_POLICY);
        regionCondition(unknown, "UNKNOWN", ValidationStatus.UNKNOWN);
        regionCondition(national, "NATIONAL", ValidationStatus.VALID);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/opportunities/{id}", unknown.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[0].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.regions[0].validationStatus").value("UNKNOWN"));
        mockMvc.perform(get("/api/opportunities/{id}", national.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[0].status").value("NATIONAL"));
    }

    @Test
    void verifiedAgeIncomeAndUnreviewedTextConditionsStayDistinct() throws Exception {
        Opportunity policy = opportunity("조건 표시", OpportunityType.YOUTH_POLICY);
        OpportunityVersion version = policy.getCurrentVersion();
        condition(version, ConditionType.AGE, "age",
                Map.of("status", "DATE_BOUND", "bornOnOrAfter", "1990-01-01",
                        "bornOnOrBefore", "2008-12-31"), ValidationStatus.VALID);
        condition(version, ConditionType.INCOME, "income",
                Map.of("status", "RANGE", "type", "MEDIAN_PERCENT", "min", "12.5", "max", "72.5",
                        "minInclusive", false, "maxInclusive", true,
                        "period", "UNKNOWN", "subject", "HOUSEHOLD",
                        "unitConfirmed", true), ValidationStatus.VALID);
        condition(version, ConditionType.OTHER, "additionalQualification",
                Map.of("status", "UNSTRUCTURED"), ValidationStatus.NEEDS_REVIEW,
                Map.of("evidenceText", "추가 자격 원문", "reason", "구조화 전"), RequirementType.REQUIRED);
        condition(version, ConditionType.OTHER, "participationRestriction",
                Map.of("status", "UNSTRUCTURED"), ValidationStatus.NEEDS_REVIEW,
                Map.of("evidenceText", "기존 수혜자는 제외", "reason", "구조화 전"), RequirementType.EXCLUSION);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/opportunities/{id}", policy.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conditions[0].bornOnOrAfter").value("1990-01-01"))
                .andExpect(jsonPath("$.conditions[0].bornOnOrBefore").value("2008-12-31"))
                .andExpect(jsonPath("$.conditions[1].incomeType").value("MEDIAN_PERCENT"))
                .andExpect(jsonPath("$.conditions[1].min").value(12.5))
                .andExpect(jsonPath("$.conditions[1].max").value(72.5))
                .andExpect(jsonPath("$.conditions[1].minInclusive").value(false))
                .andExpect(jsonPath("$.conditions[1].maxInclusive").value(true))
                .andExpect(jsonPath("$.conditions[1].period").value("UNKNOWN"))
                .andExpect(jsonPath("$.conditions[1].subject").value("HOUSEHOLD"))
                .andExpect(jsonPath("$.conditions[1].unitConfirmed").value(true))
                .andExpect(jsonPath("$.conditions[2].validationStatus").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.conditions[2].status").doesNotExist())
                .andExpect(jsonPath("$.conditions[2].evidenceText").value("추가 자격 원문"))
                .andExpect(jsonPath("$.conditions[3].requirement").value("EXCLUSION"))
                .andExpect(jsonPath("$.conditions[3].evidenceText").value("기존 수혜자는 제외"));
    }

    @Test
    void trainingRegionValidationUsesOnlyMatchingCurrentSnapshot() throws Exception {
        Opportunity training = Opportunity.builder().opportunityType(OpportunityType.VOCATIONAL_TRAINING)
                .title("지역 검증 훈련").build();
        entityManager.persist(training);
        OpportunityVersion version = OpportunityVersion.builder().opportunity(training).versionNo(1)
                .normalizedSnapshot(Map.of("region", Map.of("status", "SPECIFIC",
                        "codes", List.of("1100000000"), "validationStatus", "NEEDS_REVIEW")))
                .personalizationStatus(PersonalizationStatus.UNKNOWN).normalizationVersion("test").build();
        entityManager.persist(version);
        training.changeCurrentVersion(version);
        Region seoul = region("1100000000", "서울");
        entityManager.persist(OpportunityRegion.builder().version(version).region(seoul)
                .regionRole(RegionRole.IMPLEMENTATION).build());
        entityManager.flush();
        entityManager.clear();
        regionDirectory.refresh();

        mockMvc.perform(get("/api/opportunities/{id}", training.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[0].status").value("SPECIFIC"))
                .andExpect(jsonPath("$.regions[0].validationStatus").value("NEEDS_REVIEW"));
    }

    @Test
    void uncertainIncomeTextIsNotCoercedToANumber() {
        OpportunityCondition condition = OpportunityCondition.builder()
                .conditionType(ConditionType.INCOME).requirementType(RequirementType.REQUIRED)
                .conditionField("income").validationStatus(ValidationStatus.NEEDS_REVIEW)
                .expression(Map.of("status", "MAX_ONLY", "max", "72.5%"))
                .evidence(Map.of("evidenceText", "중위소득 72.5% 이하"))
                .build();

        OpportunityDetailResponseDto.ConditionItem item = OpportunityDetailResponseDto.ConditionItem.from(condition);
        assertNull(item.status());
        assertNull(item.max());
        assertEquals("중위소득 72.5% 이하", item.evidenceText());
    }

    @Test
    void latestEligibilityAndSavedBelongOnlyToCurrentUser() throws Exception {
        Opportunity policy = opportunity("개인화 정책", OpportunityType.YOUTH_POLICY);
        Region seoul = region("1100000000", "서울");
        User owner = account(90001L);
        User other = account(90002L);
        User noProfile = account(90003L);
        profile(owner, seoul);
        profile(other, seoul);
        UserOpportunityPreference preference = UserOpportunityPreference.builder()
                .user(owner).opportunity(policy).build();
        preference.markSaved();
        preference.markHidden();
        entityManager.persist(preference);
        result(owner, policy, EligibilityStatus.UNKNOWN, EligibilityResultService.RULE_VERSION,
                LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(10));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/opportunities/{id}", policy.getId()).with(user(principal(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibility.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.eligibility.reason").value("판정 사유"))
                .andExpect(jsonPath("$.eligibility.conditions[0].result").value("MATCHED"))
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.hidden").doesNotExist());
        mockMvc.perform(get("/api/opportunities/{id}", policy.getId()).with(user(principal(other))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibility").doesNotExist())
                .andExpect(jsonPath("$.saved").value(false));
        mockMvc.perform(get("/api/opportunities/{id}", policy.getId()).with(user(principal(noProfile))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibility").doesNotExist());
    }

    @Test
    void freshAndExpiredEligibilityAreDistinguishedWithoutRecalculation() throws Exception {
        Opportunity fresh = opportunity("최신 판정", OpportunityType.YOUTH_POLICY);
        Opportunity expired = opportunity("만료 판정", OpportunityType.YOUTH_POLICY);
        Opportunity absent = opportunity("미판정", OpportunityType.YOUTH_POLICY);
        Opportunity staleRule = opportunity("이전 규칙", OpportunityType.YOUTH_POLICY);
        User owner = account(90004L);
        profile(owner, region("1100000000", "서울"));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        result(owner, fresh, EligibilityStatus.ELIGIBLE, EligibilityResultService.RULE_VERSION, today.plusDays(10));
        result(owner, expired, EligibilityStatus.ELIGIBLE, EligibilityResultService.RULE_VERSION, today);
        result(owner, staleRule, EligibilityStatus.ELIGIBLE, "old-rule", today.plusDays(10));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/opportunities/{id}", fresh.getId()).with(user(principal(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibility.status").value("ELIGIBLE"));
        mockMvc.perform(get("/api/opportunities/{id}", expired.getId()).with(user(principal(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibility").doesNotExist());
        mockMvc.perform(get("/api/opportunities/{id}", absent.getId()).with(user(principal(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibility").doesNotExist());
        mockMvc.perform(get("/api/opportunities/{id}", staleRule.getId()).with(user(principal(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibility").doesNotExist());
    }

    @Test
    void missingAndUnnormalizedOpportunitiesReturnNotFound() throws Exception {
        Opportunity unnormalized = Opportunity.builder().opportunityType(OpportunityType.YOUTH_POLICY)
                .title("미정규화").build();
        entityManager.persist(unnormalized);
        entityManager.flush();

        mockMvc.perform(get("/api/opportunities/{id}", unnormalized.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OPPORTUNITY_001"));
        mockMvc.perform(get("/api/opportunities/{id}", 999_999_999L))
                .andExpect(status().isNotFound());
    }

    private Opportunity opportunity(String title, OpportunityType type) {
        Opportunity opportunity = Opportunity.builder().opportunityType(type).title(title).build();
        entityManager.persist(opportunity);
        opportunity.changeCurrentVersion(version(opportunity, 1));
        return opportunity;
    }

    private OpportunityVersion version(Opportunity opportunity, int number) {
        OpportunityVersion version = OpportunityVersion.builder().opportunity(opportunity).versionNo(number)
                .normalizedSnapshot(Map.of("title", opportunity.getTitle()))
                .personalizationStatus(PersonalizationStatus.UNKNOWN).normalizationVersion("test").build();
        entityManager.persist(version);
        return version;
    }

    private void window(OpportunityVersion version, LocalDate start, LocalDate end, ValidationStatus validation) {
        entityManager.persist(ApplicationWindow.builder().version(version).periodType(PeriodType.FIXED)
                .startDate(start).endDate(end).validationStatus(validation).build());
    }

    private void condition(OpportunityVersion version, ConditionType type, String field,
                           Map<String, Object> expression, ValidationStatus validation) {
        condition(version, type, field, expression, validation, Map.of(), RequirementType.REQUIRED);
    }

    private void condition(OpportunityVersion version, ConditionType type, String field,
                           Map<String, Object> expression, ValidationStatus validation,
                           Map<String, Object> evidence, RequirementType requirement) {
        entityManager.persist(OpportunityCondition.builder().version(version).conditionType(type)
                .requirementType(requirement).conditionField(field).expression(expression)
                .evidence(evidence).validationStatus(validation).build());
    }

    private void regionCondition(Opportunity opportunity, String status, ValidationStatus validation) {
        entityManager.persist(OpportunityCondition.builder().version(opportunity.getCurrentVersion())
                .conditionType(ConditionType.REGION).requirementType(RequirementType.REQUIRED)
                .conditionField("residenceRegion")
                .expression(Map.of("status", status, "role", "RESIDENCE"))
                .validationStatus(validation).build());
    }

    private Region region(String code, String name) {
        Region region = Region.builder().regionCode(code).regionName(name).build();
        entityManager.persist(region);
        return region;
    }

    private User account(Long kakaoId) {
        User account = User.builder().kakaoId(kakaoId).role(Role.USER).build();
        entityManager.persist(account);
        return account;
    }

    private void profile(User account, Region region) {
        entityManager.persist(EligibilityProfile.builder().userId(account.getId())
                .birthDate(LocalDate.of(1998, 1, 1)).residenceRegion(region)
                .employmentStatus(EmploymentStatus.UNEMPLOYED)
                .educationLevel(EducationLevel.UNIVERSITY).build());
    }

    private void result(User account, Opportunity opportunity, EligibilityStatus status,
                        String ruleVersion, LocalDate validUntil) {
        entityManager.persist(EligibilityResult.builder().userId(account.getId())
                .opportunityId(opportunity.getId()).opportunityVersionId(opportunity.getCurrentVersion().getId())
                .profileVersion(1L).ruleVersion(ruleVersion).eligibilityStatus(status).reason("판정 사유")
                .conditionResults(List.of(Map.of("type", "AGE", "field", "age", "requirement", "REQUIRED",
                        "result", "MATCHED", "reason", "조건 충족")))
                .validUntil(validUntil).evaluatedAt(LocalDateTime.now()).build());
    }

    private CustomUserDetails principal(User account) {
        return new CustomUserDetails(account.getId(), Role.USER, "detail-test");
    }
}
