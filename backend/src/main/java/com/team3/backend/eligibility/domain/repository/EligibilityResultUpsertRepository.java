package com.team3.backend.eligibility.domain.repository;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 판정 결과 일괄 저장 (EligibilityResultRepository 조각). 행마다 INSERT/UPDATE 하지 않고 SQL 1개로 묶어 저장한다.
 */
public interface EligibilityResultUpsertRepository {

    /** 저장할 판정 결과 1건. 사용자·프로필 버전·규칙 버전·판정 시각은 묶음 전체가 같다 */
    record UpsertRow(
            Long opportunityId,
            Long opportunityVersionId,
            EligibilityStatus status,
            String reason,
            List<Map<String, Object>> conditionResults,
            LocalDate validUntil
    ) {
    }

    /**
     * (user_id, opportunity_id)가 없으면 넣고 있으면 갱신한다. 아래 행은 건너뛴다.
     * - 저장된 결과의 프로필 버전이 더 높은 행: 늦게 끝난 오래된 계산이 최신 결과를 덮어쓰지 않게 한다
     * - 내용이 같은 행: 불필요한 쓰기를 하지 않는다
     *
     * @return 실제로 넣거나 갱신한 행 수
     */
    int upsert(Long userId, Long profileVersion, String ruleVersion, LocalDateTime evaluatedAt, List<UpsertRow> rows);
}
