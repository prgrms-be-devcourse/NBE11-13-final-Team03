package com.team3.backend.eligibility.service;

import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.entity.EligibilityResult;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.domain.repository.EligibilityResultRepository;
import com.team3.backend.eligibility.dto.EligibilityResultFreshness;
import com.team3.backend.eligibility.engine.ConditionEvaluation;
import com.team3.backend.eligibility.engine.EligibilityEvaluation;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.dto.OpportunityVersionRef;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.team3.backend.eligibility.exception.EligibilityErrorCode.PROFILE_NOT_FOUND;

/**
 * 판정 결과 저장·재사용 (ERD ELIGIBILITY_RESULT). 추천·검색은 이 서비스로 최신화한 뒤 저장된 결과를 조회한다.
 *
 * - refresh: 지정한 기회들(예: 추천 후보)만 최신화
 * - refreshAll: 사용자의 정규화된 기회 전체를 최신화 (신청 가능성 필터 검색 전, 프로필 변경 후)
 * 최신 결과는 다시 판정하지 않고, 없거나 오래된 결과만 판정해 저장한다 (기준은 EligibilityResult 참고).
 * 판정 결과가 없는 기회는 NOT_ELIGIBLE이 아니라 미판정이다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EligibilityResultService {

    /** 판정 규칙 버전. 판정 엔진 규칙이 바뀌면 올린다 → 기존 결과가 모두 오래된 것이 된다 */
    public static final String RULE_VERSION = "1";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CHUNK = 1_000;

    private final EligibilityProfileRepository profileRepository;
    private final EligibilityResultRepository resultRepository;
    private final OpportunityRepository opportunityRepository;
    private final EligibilityEvaluator evaluator;
    @PersistenceContext
    private EntityManager em;

    public List<EligibilityResult> refresh(Long userId, Collection<Long> opportunityIds) {
        return refresh(userId, opportunityIds, OffsetDateTime.now(KST));
    }

    /** 지정한 기회들의 판정 결과를 최신화해 돌려준다. 정규화되지 않은 기회는 결과에서 빠진다 */
    public List<EligibilityResult> refresh(Long userId, Collection<Long> opportunityIds, OffsetDateTime now) {
        EligibilityProfile profile = findProfile(userId);
        if (opportunityIds.isEmpty()) {
            return List.of();
        }
        List<OpportunityVersionRef> refs = opportunityRepository.findCurrentVersionRefs(opportunityIds);
        refreshTargets(profile, refs, today(now), now);
        return resultRepository.findByUserIdAndOpportunityIdIn(userId,
                refs.stream().map(OpportunityVersionRef::opportunityId).toList());
    }

    public int refreshAll(Long userId) {
        return refreshAll(userId, OffsetDateTime.now(KST));
    }

    /** 정규화된 기회 전체를 1,000건씩 최신화한다. @return 새로 판정한 건수 */
    public int refreshAll(Long userId, OffsetDateTime now) {
        EligibilityProfile profile = findProfile(userId);
        LocalDate today = today(now);
        int judged = 0;
        int page = 0;
        Page<OpportunityVersionRef> refs;
        do {
            refs = opportunityRepository.findAllCurrentVersionRefs(
                    PageRequest.of(page++, CHUNK, Sort.by(Sort.Direction.ASC, "id")));
            judged += refreshTargets(profile, refs.getContent(), today, now);
            resultRepository.flush();
            em.clear();
            profile = findProfile(userId);
        } while (refs.hasNext());
        return judged;
    }

    /** 없거나 오래된 결과만 판정해 저장한다. @return 판정한 건수 */
    private int refreshTargets(EligibilityProfile profile, List<OpportunityVersionRef> refs, LocalDate today,
                               OffsetDateTime now) {
        if (refs.isEmpty()) {
            return 0;
        }
        List<Long> opportunityIds = refs.stream().map(OpportunityVersionRef::opportunityId).toList();
        Map<Long, EligibilityResultFreshness> freshness = resultRepository.findFreshness(profile.getUserId(), opportunityIds)
                .stream().collect(Collectors.toMap(EligibilityResultFreshness::opportunityId, Function.identity()));

        List<OpportunityVersionRef> stale = refs.stream()
                .filter(r -> {
                    EligibilityResultFreshness f = freshness.get(r.opportunityId());
                    return f == null || !f.isFresh(r.versionId(), profile.getProfileVersion(), RULE_VERSION, today);
                })
                .toList();
        if (stale.isEmpty()) {
            return 0;
        }

        Map<Long, EligibilityEvaluator.Judgement> judgements = evaluator.judge(profile,
                stale.stream().map(OpportunityVersionRef::versionId).collect(Collectors.toSet()), today);
        Map<Long, EligibilityResult> existing = resultRepository.findByUserIdAndOpportunityIdIn(profile.getUserId(),
                        stale.stream().map(OpportunityVersionRef::opportunityId).filter(freshness::containsKey).toList())
                .stream().collect(Collectors.toMap(EligibilityResult::getOpportunityId, Function.identity()));

        LocalDate validUntil = nextBirthday(profile.getBirthDate(), today);
        var evaluatedAt = now.atZoneSameInstant(KST).toLocalDateTime();
        List<EligibilityResult> created = new ArrayList<>();
        for (OpportunityVersionRef ref : stale) {
            EligibilityEvaluation evaluation = judgements.get(ref.versionId()).evaluation();
            List<Map<String, Object>> conditions = evaluation.conditions().stream()
                    .map(EligibilityResultService::toMap).toList();
            EligibilityResult current = existing.get(ref.opportunityId());
            if (current != null) {
                current.update(ref.versionId(), profile.getProfileVersion(), RULE_VERSION, evaluation.status(),
                        evaluation.reason(), conditions, validUntil, evaluatedAt);
            } else {
                created.add(EligibilityResult.builder()
                        .userId(profile.getUserId())
                        .opportunityId(ref.opportunityId())
                        .opportunityVersionId(ref.versionId())
                        .profileVersion(profile.getProfileVersion())
                        .ruleVersion(RULE_VERSION)
                        .eligibilityStatus(evaluation.status())
                        .reason(evaluation.reason())
                        .conditionResults(conditions)
                        .validUntil(validUntil)
                        .evaluatedAt(evaluatedAt)
                        .build());
            }
        }
        resultRepository.saveAll(created);
        return stale.size();
    }

    /** 만 나이가 바뀌는 다음 생일 (오늘이 생일이면 내년 생일). 2월 29일생은 평년에 2월 28일 */
    static LocalDate nextBirthday(LocalDate birthDate, LocalDate today) {
        if (birthDate == null) {
            return null;
        }
        LocalDate thisYear = birthDate.withYear(today.getYear());
        return thisYear.isAfter(today) ? thisYear : birthDate.withYear(today.getYear() + 1);
    }

    private static Map<String, Object> toMap(ConditionEvaluation c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", c.type() == null ? null : c.type().name());
        m.put("field", c.field());
        m.put("requirement", c.requirement() == null ? null : c.requirement().name());
        m.put("operator", c.operator());
        m.put("expected", jsonSafe(c.expected()));
        m.put("actual", jsonSafe(c.actual()));
        m.put("result", c.result().name());
        m.put("reason", c.reason());
        return m;
    }

    /** jsonb에 넣을 수 있는 값으로 (문자열·숫자·불리언·목록·맵 외에는 문자열로) */
    private static Object jsonSafe(Object v) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Enum<?> e) {
            return e.name();
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, val) -> out.put(String.valueOf(k), jsonSafe(val)));
            return out;
        }
        if (v instanceof Collection<?> col) {
            return col.stream().map(EligibilityResultService::jsonSafe).toList();
        }
        return String.valueOf(v);
    }

    private static LocalDate today(OffsetDateTime now) {
        return now.atZoneSameInstant(KST).toLocalDate();
    }

    private EligibilityProfile findProfile(Long userId) {
        return profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(PROFILE_NOT_FOUND));
    }
}
