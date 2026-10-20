package com.team3.backend.eligibility.api;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.domain.repository.EligibilityResultRepository;
import com.team3.backend.eligibility.dto.EligibilityResultFreshness;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.region.domain.entity.Region;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static com.team3.backend.eligibility.domain.entity.EligibilityStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EligibilitySpecifications(SQL)가 판정 서비스의 최신 여부 판단(EligibilityResultFreshness.isFresh)과 같은 기회를 고르는지
 * 실제 PostgreSQL로 확인한다. 기회 버전·프로필 버전·규칙 버전·validUntil·상태를 모두 조합한다. 일회용 DB를 YON_TEST_DB_URL로 지정할 것.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "YON_TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=${YON_TEST_DB_URL}",
        "spring.datasource.username=${YON_TEST_DB_USERNAME:postgres}",
        "spring.datasource.password=${YON_TEST_DB_PASSWORD:1234}",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class EligibilitySpecificationsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;
    private static final Long NO_PROFILE_USER = 3L;
    private static final Long PROFILE_VERSION = 2L;
    private static final String RULE = EligibilityResultService.RULE_VERSION;

    /** validUntil 경우: 없음, 어제, 오늘(만료), 내일 */
    private static final List<LocalDate> VALID_UNTILS = Arrays.asList(null, TODAY.minusDays(1), TODAY, TODAY.plusDays(1));

    @PersistenceContext
    private EntityManager em;
    @Autowired
    private EligibilityResultRepository resultRepository;

    /** 기회 ID → 사용자 USER의 저장된 결과 (없으면 null) */
    private final Map<Long, EligibilityResult> resultByOpportunity = new LinkedHashMap<>();
    private final Map<Long, Long> currentVersionByOpportunity = new HashMap<>();
    private Long withoutVersionId;

    @BeforeEach
    void setUp() {
        Region region = Region.builder().regionCode("1168000000").regionName("강남구").build();
        em.persist(region);
        for (Long userId : List.of(USER, OTHER_USER)) {
            em.persist(EligibilityProfile.builder()
                    .userId(userId)
                    .birthDate(LocalDate.of(1998, 5, 15))
                    .residenceRegion(region)
                    .employmentStatus(EmploymentStatus.UNEMPLOYED)
                    .educationLevel(EducationLevel.UNIVERSITY)
                    .profileVersion(PROFILE_VERSION)
                    .build());
        }

        for (boolean sameVersion : List.of(true, false)) {
            for (long profileVersion : List.of(PROFILE_VERSION, PROFILE_VERSION - 1)) {
                for (String ruleVersion : List.of(RULE, RULE + "-old")) {
                    for (LocalDate validUntil : VALID_UNTILS) {
                        for (EligibilityStatus status : EligibilityStatus.values()) {
                            Opportunity o = opportunityWithVersion();
                            Long versionId = currentVersionByOpportunity.get(o.getId());
                            resultByOpportunity.put(o.getId(), persistResult(USER, o.getId(),
                                    sameVersion ? versionId : versionId + 1_000_000L,
                                    profileVersion, ruleVersion, validUntil, status));
                        }
                    }
                }
            }
        }

        Opportunity noResult = opportunityWithVersion();
        resultByOpportunity.put(noResult.getId(), null);

        Opportunity otherUsersOnly = opportunityWithVersion();
        persistResult(OTHER_USER, otherUsersOnly.getId(), currentVersionByOpportunity.get(otherUsersOnly.getId()),
                PROFILE_VERSION, RULE, null, ELIGIBLE);
        resultByOpportunity.put(otherUsersOnly.getId(), null);

        Opportunity withoutVersion = Opportunity.builder()
                .opportunityType(OpportunityType.YOUTH_POLICY).title("현재 버전 없음").build();
        em.persist(withoutVersion);
        withoutVersionId = withoutVersion.getId();
        persistResult(USER, withoutVersionId, 999_999L, PROFILE_VERSION, RULE, null, ELIGIBLE);

        em.flush();
        em.clear();
    }

    @Test
    void 모든_조합에서_isFresh와_같은_기회를_고른다() {
        for (EligibilityStatus status : EligibilityStatus.values()) {
            assertThat(ids(EligibilitySpecifications.statusIn(USER, List.of(status), TODAY)))
                    .as("상태 %s", status)
                    .containsExactlyInAnyOrderElementsOf(expected(Set.of(status)));
        }
        assertThat(expected(Set.of(ELIGIBLE))).isNotEmpty();
    }

    @Test
    void 여러_상태를_한_번에_거르고_건수도_같다() {
        Specification<Opportunity> spec = EligibilitySpecifications.statusIn(USER, List.of(ELIGIBLE, POSSIBLY_ELIGIBLE), TODAY);
        Set<Long> expected = expected(Set.of(ELIGIBLE, POSSIBLY_ELIGIBLE));

        assertThat(ids(spec)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(count(spec)).isEqualTo(expected.size());
        assertThat(ids(spec)).doesNotContain(withoutVersionId);
    }

    @Test
    void 프로필이_없는_사용자나_빈_상태_목록은_아무것도_고르지_않는다() {
        assertThat(ids(EligibilitySpecifications.statusIn(NO_PROFILE_USER, List.of(ELIGIBLE), TODAY))).isEmpty();
        assertThat(ids(EligibilitySpecifications.statusIn(USER, List.of(), TODAY))).isEmpty();
    }

    @Test
    void 다른_사용자의_결과는_섞이지_않는다() {
        Set<Long> mine = new HashSet<>(ids(EligibilitySpecifications.statusIn(USER, List.of(ELIGIBLE), TODAY)));
        List<Long> others = ids(EligibilitySpecifications.statusIn(OTHER_USER, List.of(ELIGIBLE), TODAY));

        assertThat(others).hasSize(1);
        assertThat(mine).doesNotContainAnyElementsOf(others);
    }

    @Test
    void 상태_일괄_조회도_같은_최신_기준으로_상태를_돌려준다() {
        List<Long> all = new ArrayList<>(resultByOpportunity.keySet());
        all.add(withoutVersionId);

        Map<Long, EligibilityStatus> actual = new HashMap<>();
        resultRepository.findFreshStatuses(USER, all, RULE, TODAY)
                .forEach(row -> actual.put(row.opportunityId(), row.status()));

        Map<Long, EligibilityStatus> expected = new HashMap<>();
        for (Long id : expected(Set.of(EligibilityStatus.values()))) {
            expected.put(id, resultByOpportunity.get(id).getEligibilityStatus());
        }
        assertThat(actual).isEqualTo(expected);
        assertThat(actual).containsValue(UNKNOWN);
        assertThat(resultRepository.findFreshStatuses(NO_PROFILE_USER, all, RULE, TODAY)).isEmpty();
    }

    /** 판정 서비스 기준(isFresh)으로 계산한 기대값 */
    private Set<Long> expected(Set<EligibilityStatus> statuses) {
        Set<Long> ids = new HashSet<>();
        resultByOpportunity.forEach((opportunityId, r) -> {
            if (r == null) {
                return;
            }
            boolean fresh = new EligibilityResultFreshness(r.getOpportunityId(), r.getOpportunityVersionId(),
                    r.getProfileVersion(), r.getRuleVersion(), r.getValidUntil())
                    .isFresh(currentVersionByOpportunity.get(opportunityId), PROFILE_VERSION, RULE, TODAY);
            if (fresh && statuses.contains(r.getEligibilityStatus())) {
                ids.add(opportunityId);
            }
        });
        return ids;
    }

    private List<Long> ids(Specification<Opportunity> spec) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<Opportunity> root = q.from(Opportunity.class);
        q.select(root.get("id")).where(spec.toPredicate(root, q, cb));
        return em.createQuery(q).getResultList();
    }

    private long count(Specification<Opportunity> spec) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<Opportunity> root = q.from(Opportunity.class);
        q.select(cb.count(root)).where(spec.toPredicate(root, q, cb));
        return em.createQuery(q).getSingleResult();
    }

    private Opportunity opportunityWithVersion() {
        Opportunity opportunity = Opportunity.builder()
                .opportunityType(OpportunityType.YOUTH_POLICY)
                .title("기회 " + (resultByOpportunity.size() + 1))
                .build();
        em.persist(opportunity);
        OpportunityVersion version = OpportunityVersion.builder()
                .opportunity(opportunity)
                .versionNo(1)
                .normalizedSnapshot(Map.of())
                .personalizationStatus(PersonalizationStatus.UNKNOWN)
                .normalizationVersion("test")
                .build();
        em.persist(version);
        opportunity.changeCurrentVersion(version);
        currentVersionByOpportunity.put(opportunity.getId(), version.getId());
        return opportunity;
    }

    private EligibilityResult persistResult(Long userId, Long opportunityId, Long versionId, Long profileVersion,
                                            String ruleVersion, LocalDate validUntil, EligibilityStatus status) {
        EligibilityResult result = EligibilityResult.builder()
                .userId(userId)
                .opportunityId(opportunityId)
                .opportunityVersionId(versionId)
                .profileVersion(profileVersion)
                .ruleVersion(ruleVersion)
                .eligibilityStatus(status)
                .conditionResults(List.of())
                .validUntil(validUntil)
                .evaluatedAt(LocalDateTime.of(2026, 10, 19, 9, 0))
                .build();
        em.persist(result);
        return result;
    }
}
