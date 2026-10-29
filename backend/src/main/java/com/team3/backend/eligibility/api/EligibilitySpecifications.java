package com.team3.backend.eligibility.api;

import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.entity.EligibilityResult;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.dto.EligibilityResultFreshness;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 신청 가능성 검색 필터. 저장된 최신 판정 결과(eligibility_set_results, 사용자 × 조건 묶음)로만 거르고, 재판정은 하지 않는다 (DB 조회 전용).
 * 검색 조건과 AND로 묶어 정렬·페이지네이션·전체 건수를 DB에서 정확히 내기 위해 Specification으로 제공한다.
 *
 * 최신 판정 결과 기준은 EligibilityResultFreshness.isFresh와 같다. 아래를 모두 만족하는 결과만 포함한다.
 * - 판정한 조건 묶음 = 기회 현재 버전의 조건 묶음 (현재 버전이나 조건 묶음이 없는 기회는 제외)
 * - 판정한 프로필 버전 = 사용자 판정 프로필의 현재 버전 (프로필이 없으면 제외)
 * - 규칙 버전 = EligibilityResultService.RULE_VERSION
 * - validUntil이 없거나 오늘보다 뒤
 * 판정 결과가 없거나 오래된 기회는 결과에서 빠진다. 최신화는 정책 엔진이 맡는다 (현재는 프로필 변경 시 비동기 전체 판정).
 * 규칙을 바꿀 때는 isFresh와 이 클래스를 함께 바꾸고 EligibilitySpecificationsTest로 일치를 확인한다.
 *
 * 기준일(today)은 한국 시간 날짜를 넘긴다 (DB의 현재 시각을 쓰지 않는다).
 * 사용 예: opportunityRepository.findAll(where(keyword).and(EligibilitySpecifications.statusIn(userId, List.of(ELIGIBLE, POSSIBLY_ELIGIBLE), today)), pageable)
 *
 * @see EligibilityResultFreshness#isFresh
 */
public final class EligibilitySpecifications {

    private EligibilitySpecifications() {
    }

    /** 사용자의 최신 판정 결과 상태가 statuses 중 하나인 기회. statuses가 비어 있으면 아무것도 고르지 않는다 */
    public static Specification<Opportunity> statusIn(Long userId, Collection<EligibilityStatus> statuses, LocalDate today) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(statuses, "statuses");
        Objects.requireNonNull(today, "today");
        List<EligibilityStatus> wanted = statuses.stream().distinct().toList();
        return (root, query, cb) -> {
            if (wanted.isEmpty()) {
                return cb.disjunction();
            }
            Subquery<Long> result = query.subquery(Long.class);
            Root<EligibilityResult> r = result.from(EligibilityResult.class);

            Subquery<Long> currentProfileVersion = result.subquery(Long.class);
            Root<EligibilityProfile> p = currentProfileVersion.from(EligibilityProfile.class);
            currentProfileVersion.select(p.get("profileVersion")).where(cb.equal(p.get("userId"), userId));

            Path<LocalDate> validUntil = r.get("validUntil");
            result.select(r.get("id")).where(
                    cb.equal(r.get("userId"), userId),
                    cb.equal(r.get("conditionSetId"), root.get("currentVersion").get("conditionSetId")),
                    cb.equal(r.get("profileVersion"), currentProfileVersion),
                    cb.equal(r.get("ruleVersion"), EligibilityResultService.RULE_VERSION),
                    cb.or(validUntil.isNull(), cb.greaterThan(validUntil, today)),
                    r.get("eligibilityStatus").in(wanted));
            return cb.exists(result);
        };
    }
}
