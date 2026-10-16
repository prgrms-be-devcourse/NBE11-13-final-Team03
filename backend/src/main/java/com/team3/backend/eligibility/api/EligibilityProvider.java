package com.team3.backend.eligibility.api;

import com.team3.backend.eligibility.api.dto.EligibilitySnapshot;
import com.team3.backend.eligibility.api.dto.EligibilitySnapshotPage;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;

import java.util.Collection;
import java.util.List;

/**
 * 다른 영역(추천 등)이 판정 결과를 받아 가는 유일한 진입점.
 * 호출하는 쪽은 이 인터페이스와 api.dto만 의존하고, 정책 엔진의 엔티티·서비스·레포지토리를 직접 쓰지 않는다.
 *
 * 구현 선택 (yon.eligibility.provider)
 * - local (기본): 같은 애플리케이션 안에서 직접 호출 → LocalEligibilityProvider
 * - MSA 전환 시: HTTP 등 원격 구현을 추가하고 이 값을 바꾼다. 호출하는 쪽 코드는 바뀌지 않는다
 *
 * 두 메서드 모두 오래되거나 없는 판정 결과를 먼저 다시 판정한 뒤 돌려준다.
 * 결과마다 추천에 필요한 기회 요약(OpportunityInfo: 분류, NCS, 모집 상태, 마감일, 교육 시작일, 등록일)을 함께 담는다.
 * 판정 프로필이 없는 사용자는 BusinessException(PROFILE_NOT_FOUND).
 */
public interface EligibilityProvider {

    /**
     * 지정한 기회들의 최신 판정 결과 (예: 추천 후보). 요청한 순서대로 돌려준다.
     * 정규화되지 않은 기회는 결과에서 빠진다 (= 미판정, NOT_ELIGIBLE 아님).
     */
    List<EligibilitySnapshot> getEligibilities(Long userId, Collection<Long> opportunityIds);

    /**
     * 판정 상태로 거른 최신 판정 결과 페이지 (예: ELIGIBLE, POSSIBLY_ELIGIBLE). 기회 ID 오름차순.
     * 사용자의 정규화된 기회 전체를 먼저 최신화하므로 단건 조회보다 무겁다.
     */
    EligibilitySnapshotPage findByStatuses(Long userId, Collection<EligibilityStatus> statuses, int page, int size);
}
