package com.team3.backend.eligibility.service;

import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.domain.repository.EligibilityResultRepository;
import com.team3.backend.eligibility.domain.repository.EligibilityResultUpsertRepository.UpsertRow;
import com.team3.backend.eligibility.dto.ConditionSetRef;
import com.team3.backend.eligibility.dto.EligibilityResultFreshness;
import com.team3.backend.eligibility.dto.OpportunityEligibility;
import com.team3.backend.eligibility.engine.ConditionEvaluation;
import com.team3.backend.eligibility.engine.EligibilityEvaluation;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.dto.OpportunitySetRef;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
 * 판정 결과는 (사용자, 조건 묶음) 단위로 저장한다. 기회의 결과 = 기회 현재 버전의 조건 묶음 결과 (EligibilityResult 참고).
 * 조건 묶음마다 대표 버전 하나로 판정한다 (같은 묶음이면 판정 입력이 같아 결과가 같다).
 *
 * - refresh: 지정한 기회들(예: 추천 후보)의 조건 묶음만 최신화
 * - refreshAll / refreshStale: 사용자의 결과가 없거나 오래된 조건 묶음을 모두 최신화 (프로필 변경 후, 배치)
 * 최신 결과는 다시 판정하지 않고, 없거나 오래된 결과만 판정해 저장한다.
 * 판정 결과가 없는 기회는 NOT_ELIGIBLE이 아니라 미판정이다.
 * 저장은 yon.eligibility.result-write-batch-size(기본 1,000)건씩 SQL 1개로 묶어 한다 (EligibilityResultUpsertRepository).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EligibilityResultService {

    /** 판정 규칙 버전. 판정 엔진 규칙이 바뀌면 올린다 → 기존 결과가 모두 오래된 것이 된다
     *  1 → 2 (2026-10-19): 국민내일배움카드(TRAINING_CARD)·K-디지털 트레이닝 1회 한정(PARTICIPATION_HISTORY) 판정 추가
     *  2 → 3 (2026-10-21): 판정 결과를 조건 묶음 단위로 저장 (새 테이블, 조건별 결과 순서 고정)
     *  3 → 4 (2026-10-22): UNVERIFIED 조건은 일치 → CHECK_REQUIRED, 불일치 → DATA_UNCLEAR */
    public static final String RULE_VERSION = "4";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CHUNK = 1_000;
    /** refreshStale 진행 로그 간격 (묶음 수) */
    private static final int PROGRESS_LOG_CHUNKS = 10;

    private final EligibilityProfileRepository profileRepository;
    private final EligibilityResultRepository resultRepository;
    private final OpportunityRepository opportunityRepository;
    private final EligibilityEvaluator evaluator;
    private final PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager em;
    /** 저장 SQL 1개에 담는 행 수. refreshAll은 CHUNK건씩 판정하므로 CHUNK보다 크게 잡아도 한 번에 CHUNK건까지만 담긴다 */
    @Value("${yon.eligibility.result-write-batch-size:1000}")
    private int writeBatchSize = 1_000;

    public List<OpportunityEligibility> refresh(Long userId, Collection<Long> opportunityIds) {
        return refresh(userId, opportunityIds, OffsetDateTime.now(KST));
    }

    /**
     * 지정한 기회들의 판정 결과를 최신화해 돌려준다 (기회별, 순서 보장 없음).
     * 정규화되지 않았거나 조건 묶음이 아직 없는 기회는 결과에서 빠진다 (= 미판정).
     */
    public List<OpportunityEligibility> refresh(Long userId, Collection<Long> opportunityIds, OffsetDateTime now) {
        EligibilityProfile profile = findProfile(userId);
        if (opportunityIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> representative = new LinkedHashMap<>();
        for (OpportunitySetRef ref : opportunityRepository.findCurrentSetRefs(opportunityIds)) {
            if (ref.conditionSetId() != null) {
                representative.putIfAbsent(ref.conditionSetId(), ref.versionId());
            }
        }
        List<ConditionSetRef> sets = representative.entrySet().stream()
                .map(e -> new ConditionSetRef(e.getKey(), e.getValue())).toList();
        if (refreshTargets(profile, sets, today(now), now) > 0) {
            // 저장은 영속성 컨텍스트를 거치지 않으므로, 이미 읽어 둔 결과 엔티티가 있으면 버리고 새로 읽는다
            em.flush();
            em.clear();
        }
        return resultRepository.findByOpportunities(userId, opportunityIds);
    }

    public int refreshAll(Long userId) {
        return refreshAll(userId, OffsetDateTime.now(KST));
    }

    /**
     * 사용자의 결과가 없거나 오래된 조건 묶음을 모두 최신화한다 (refreshStale과 같고, 판정 프로필이 없으면 PROFILE_NOT_FOUND).
     * 바깥 트랜잭션 안에서 부르면 거기에 합류한다. @return 새로 판정한 조건 묶음 수
     */
    public int refreshAll(Long userId, OffsetDateTime now) {
        findProfile(userId);
        return refreshStale(userId, now);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public int refreshStale(Long userId) {
        return refreshStale(userId, OffsetDateTime.now(KST));
    }

    /**
     * 판정 결과가 없거나 오래된 조건 묶음만 DB에서 1,000개씩 골라 최신화한다 (새 조건 묶음, 생일·프로필·규칙 변경).
     * 기회에 새 버전이 생겨도 조건 묶음이 같으면 다시 판정하지 않는다. 판정 프로필이 없으면 0을 돌려준다(예외 없음).
     *
     * 트랜잭션: 바깥 트랜잭션이 없으면(배치) 1,000건 묶음마다 따로 커밋한다. 실패해도 앞 묶음은 남고, 다음 실행이 남은 것부터 처리한다.
     * 바깥 트랜잭션이 있으면(테스트 등) 거기에 합류한다.
     * 통계: 판정 결과 테이블에 열 통계가 없으면(새로 만든 테이블) 첫 묶음을 커밋한 뒤 ANALYZE를 한 번 한다.
     * 통계가 없으면 PostgreSQL이 결과 테이블을 빈 것으로 보고 기회마다 테이블을 훑는 계획을 골라, 첫 사용자 판정이
     * 크게 느려진다 (실데이터 14.1만 건: 통계 없음 300초, 계획 캐시까지 끄면 136초, 통계 있음 9초. 2026-10-21 홈서버).
     * @return 새로 판정한 조건 묶음 수
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public int refreshStale(Long userId, OffsetDateTime now) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        LocalDate today = today(now);
        boolean analyzeAfterFirstChunk = Boolean.TRUE.equals(tx.execute(status -> resultRepository.lacksStatistics()));
        int judged = 0;
        int chunks = 0;
        long afterConditionSetId = 0L;
        while (true) {
            long cursor = afterConditionSetId;
            StaleChunk chunk = tx.execute(status -> refreshStaleChunk(userId, today, now, cursor));
            if (chunk == null || chunk.fetched() == 0) {
                break;
            }
            judged += chunk.judged();
            chunks++;
            afterConditionSetId = chunk.lastConditionSetId();
            if (analyzeAfterFirstChunk) {
                analyzeAfterFirstChunk = false;
                analyzeResults(tx, userId);
            }
            if (chunks % PROGRESS_LOG_CHUNKS == 0) {
                log.info("Eligibility stale refresh in progress: userId={}, chunks={}, judged={}", userId, chunks, judged);
            }
            if (chunk.fetched() < CHUNK) {
                break;
            }
        }
        return judged;
    }

    /** 통계 갱신은 성능 보조라, 실패해도(권한 등) 판정은 계속한다 */
    private void analyzeResults(TransactionTemplate tx, Long userId) {
        try {
            tx.executeWithoutResult(status -> resultRepository.analyze());
            log.info("Eligibility results analyzed after first chunk (table had no statistics): userId={}", userId);
        } catch (RuntimeException e) {
            log.warn("Eligibility results analyze failed, continuing without statistics: userId={}", userId, e);
        }
    }

    /** 묶음 1개 (현재 트랜잭션 안에서). 프로필이 없어지면 fetched=0으로 멈춘다 */
    private StaleChunk refreshStaleChunk(Long userId, LocalDate today, OffsetDateTime now, long afterConditionSetId) {
        Optional<EligibilityProfile> found = profileRepository.findByUserId(userId);
        if (found.isEmpty()) {
            return new StaleChunk(0, 0, afterConditionSetId);
        }
        EligibilityProfile profile = found.get();
        List<ConditionSetRef> sets = resultRepository.findStaleConditionSets(userId, profile.getProfileVersion(),
                RULE_VERSION, today, afterConditionSetId, PageRequest.of(0, CHUNK));
        if (sets.isEmpty()) {
            return new StaleChunk(0, 0, afterConditionSetId);
        }
        int judged = refreshTargets(profile, sets, today, now);
        resultRepository.flush();
        em.clear();
        return new StaleChunk(sets.size(), judged, sets.getLast().conditionSetId());
    }

    private record StaleChunk(int fetched, int judged, long lastConditionSetId) {
    }

    /** 없거나 오래된 조건 묶음 결과만 대표 버전으로 판정해 저장한다. @return 판정한 조건 묶음 수 */
    private int refreshTargets(EligibilityProfile profile, List<ConditionSetRef> sets, LocalDate today,
                               OffsetDateTime now) {
        if (sets.isEmpty()) {
            return 0;
        }
        List<Long> setIds = sets.stream().map(ConditionSetRef::conditionSetId).toList();
        Map<Long, EligibilityResultFreshness> freshness = resultRepository.findFreshness(profile.getUserId(), setIds)
                .stream().collect(Collectors.toMap(EligibilityResultFreshness::conditionSetId, Function.identity()));

        List<ConditionSetRef> stale = sets.stream()
                .filter(s -> {
                    EligibilityResultFreshness f = freshness.get(s.conditionSetId());
                    return f == null || !f.isFresh(profile.getProfileVersion(), RULE_VERSION, today);
                })
                .toList();
        if (stale.isEmpty()) {
            return 0;
        }

        Map<Long, EligibilityEvaluator.Judgement> judgements = evaluator.judge(profile,
                stale.stream().map(ConditionSetRef::versionId).collect(Collectors.toSet()), today);

        LocalDate validUntil = nextBirthday(profile.getBirthDate(), today);
        var evaluatedAt = now.atZoneSameInstant(KST).toLocalDateTime();
        List<UpsertRow> rows = new ArrayList<>(stale.size());
        for (ConditionSetRef ref : stale) {
            EligibilityEvaluation evaluation = judgements.get(ref.versionId()).evaluation();
            List<Map<String, Object>> conditions = evaluation.conditions().stream()
                    .map(EligibilityResultService::toMap).toList();
            rows.add(new UpsertRow(ref.conditionSetId(), evaluation.status(), evaluation.reason(), conditions,
                    validUntil));
        }
        int batchSize = Math.max(1, writeBatchSize);
        for (int from = 0; from < rows.size(); from += batchSize) {
            resultRepository.upsert(profile.getUserId(), profile.getProfileVersion(), RULE_VERSION, evaluatedAt,
                    rows.subList(from, Math.min(from + batchSize, rows.size())));
        }
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
