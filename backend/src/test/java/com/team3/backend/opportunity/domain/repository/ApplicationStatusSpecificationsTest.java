package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.*;

import static com.team3.backend.opportunity.domain.status.ApplicationStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApplicationStatusSpecifications(SQL)와 ApplicationStatusCalculator(Java)가 같은 신청 상태를 내는지 실제 PostgreSQL로 확인한다.
 * 신청기간 유형을 하나씩, 그리고 둘씩 조합한 기회를 모두 만들어 비교한다. 일회용 DB를 YON_TEST_DB_URL로 지정할 것.
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
class ApplicationStatusSpecificationsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate PAST = TODAY.minusDays(10);
    private static final LocalDate FUTURE = TODAY.plusDays(10);

    /** 신청기간 한 건의 경우 (경계값·한쪽 날짜만 있음·날짜 없음·검증 실패 포함) */
    private static final List<ApplicationPeriod> CASES = List.of(
            fixed(PAST, PAST.plusDays(1)),          // 종료
            fixed(PAST, FUTURE),                    // 진행 중
            fixed(FUTURE, FUTURE.plusDays(5)),      // 예정
            fixed(TODAY, TODAY),                    // 오늘 하루
            fixed(PAST, TODAY),                     // 오늘 종료
            fixed(TODAY, FUTURE),                   // 오늘 시작
            fixed(null, PAST),                      // 종료일만, 지남
            fixed(null, FUTURE),                    // 종료일만, 남음
            fixed(PAST, null),                      // 시작일만, 시작함
            fixed(FUTURE, null),                    // 시작일만, 시작 전
            fixed(null, null),                      // 날짜 없음
            fixed(FUTURE, PAST),                    // 시작 > 종료 (잘못된 값)
            new ApplicationPeriod(PeriodType.ALWAYS, null, null, ValidationStatus.VALID, null),
            new ApplicationPeriod(PeriodType.CLOSED, null, null, ValidationStatus.VALID, null),
            new ApplicationPeriod(PeriodType.UNKNOWN, null, null, ValidationStatus.UNKNOWN, null),
            new ApplicationPeriod(PeriodType.FIXED, PAST, FUTURE, ValidationStatus.INVALID, null),
            new ApplicationPeriod(PeriodType.ALWAYS, null, null, ValidationStatus.NEEDS_REVIEW, null));

    private final ApplicationStatusCalculator calculator = new ApplicationStatusCalculator();

    @PersistenceContext
    private EntityManager em;

    /** 기회 ID → 그 기회의 신청기간 */
    private final Map<Long, List<ApplicationPeriod>> periodsById = new LinkedHashMap<>();
    private Long withoutVersionId;

    @BeforeEach
    void setUp() {
        persist(List.of());
        for (ApplicationPeriod a : CASES) {
            persist(List.of(a));
        }
        for (int i = 0; i < CASES.size(); i++) {
            for (int j = i + 1; j < CASES.size(); j++) {
                persist(List.of(CASES.get(i), CASES.get(j)));
            }
        }
        Opportunity withoutVersion = opportunity("현재 버전 없음");
        em.persist(withoutVersion);
        withoutVersionId = withoutVersion.getId();
        em.flush();
        em.clear();
    }

    @Test
    void 모든_신청기간_조합에서_Calculator와_같은_상태를_낸다() {
        Map<Long, ApplicationStatus> byQuery = new HashMap<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            for (Long id : ids(ApplicationStatusSpecifications.hasStatus(status, TODAY))) {
                assertThat(byQuery.put(id, status)).as("기회 %d는 상태가 하나여야 한다", id).isNull();
            }
        }

        periodsById.forEach((id, periods) -> assertThat(byQuery.get(id))
                .as("신청기간 %s", periods)
                .isEqualTo(calculator.calculate(periods, TODAY)));
        assertThat(byQuery.get(withoutVersionId)).isEqualTo(UNKNOWN);
        assertThat(byQuery).hasSize(periodsById.size() + 1);
    }

    @Test
    void 여러_상태를_한_번에_거르고_건수도_같다() {
        Set<Long> expected = new HashSet<>();
        periodsById.forEach((id, periods) -> {
            ApplicationStatus s = calculator.calculate(periods, TODAY);
            if (s == OPEN || s == ALWAYS_OPEN) {
                expected.add(id);
            }
        });

        Specification<Opportunity> recruiting = ApplicationStatusSpecifications.statusIn(List.of(OPEN, ALWAYS_OPEN), TODAY);

        assertThat(ids(recruiting)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(count(recruiting)).isEqualTo(expected.size());
        assertThat(expected).isNotEmpty();
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

    private void persist(List<ApplicationPeriod> periods) {
        Opportunity opportunity = opportunity("기회 " + (periodsById.size() + 1));
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
        for (ApplicationPeriod p : periods) {
            em.persist(ApplicationWindow.builder()
                    .version(version)
                    .periodType(p.periodType())
                    .startDate(p.startDate())
                    .endDate(p.endDate())
                    .validationStatus(p.validationStatus())
                    .build());
        }
        periodsById.put(opportunity.getId(), periods);
    }

    private static Opportunity opportunity(String title) {
        return Opportunity.builder()
                .opportunityType(OpportunityType.YOUTH_POLICY)
                .title(title)
                .build();
    }

    private static ApplicationPeriod fixed(LocalDate start, LocalDate end) {
        return new ApplicationPeriod(PeriodType.FIXED, start, end, ValidationStatus.VALID, null);
    }
}
