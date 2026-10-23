package com.team3.backend.eligibility.support;

import com.team3.backend.eligibility.domain.entity.EligibilityResult;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import jakarta.persistence.EntityManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 판정 결과 테스트 데이터. 판정 결과는 (사용자 × 조건 묶음) 단위라, 정규화를 거치지 않고 만든 기회 버전에는
 * 조건 묶음을 먼저 붙여야 결과가 그 기회에 연결된다.
 */
public final class EligibilityResultFixtures {

    private EligibilityResultFixtures() {
    }

    /**
     * 버전의 조건 묶음 ID. 없으면 이 버전만의 새 묶음을 만들어 붙인다.
     * 버전 엔티티가 불변(@Immutable)이라 DB는 네이티브 SQL로, 메모리의 엔티티는 필드로 함께 맞춘다.
     */
    public static Long conditionSetOf(EntityManager em, OpportunityVersion version) {
        if (version.getConditionSetId() != null) {
            return version.getConditionSetId();
        }
        Long setId = newConditionSet(em);
        em.flush();
        em.createNativeQuery("UPDATE opportunity_versions SET condition_set_id = :setId WHERE id = :versionId")
                .setParameter("setId", setId)
                .setParameter("versionId", version.getId())
                .executeUpdate();
        ReflectionTestUtils.setField(version, "conditionSetId", setId);
        return setId;
    }

    /**
     * 어느 버전에도 붙지 않은 새 조건 묶음 (다른 묶음에 대한 결과 = 현재 버전 기준으로는 오래된 결과).
     * 조건 묶음 엔티티는 생성자를 열지 않으므로(묶음은 data-service만 만든다) 네이티브 SQL로 넣는다
     */
    public static Long newConditionSet(EntityManager em) {
        String hash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
        em.createNativeQuery("INSERT INTO condition_sets (condition_hash, created_at) VALUES (:hash, CURRENT_TIMESTAMP)")
                .setParameter("hash", hash)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM condition_sets WHERE condition_hash = :hash")
                .setParameter("hash", hash)
                .getSingleResult()).longValue();
    }

    /** 기회의 현재 버전 조건 묶음에 대한 결과 */
    public static EligibilityResult result(EntityManager em, Long userId, Opportunity opportunity,
                                           EligibilityStatus status, Long profileVersion, String ruleVersion,
                                           LocalDate validUntil) {
        return result(em, userId, conditionSetOf(em, opportunity.getCurrentVersion()), status, profileVersion,
                ruleVersion, validUntil);
    }

    public static EligibilityResult result(EntityManager em, Long userId, Long conditionSetId,
                                           EligibilityStatus status, Long profileVersion, String ruleVersion,
                                           LocalDate validUntil) {
        EligibilityResult result = EligibilityResult.builder()
                .userId(userId).conditionSetId(conditionSetId)
                .profileVersion(profileVersion).ruleVersion(ruleVersion)
                .eligibilityStatus(status).conditionResults(List.of())
                .validUntil(validUntil).evaluatedAt(LocalDateTime.now()).build();
        em.persist(result);
        return result;
    }
}
