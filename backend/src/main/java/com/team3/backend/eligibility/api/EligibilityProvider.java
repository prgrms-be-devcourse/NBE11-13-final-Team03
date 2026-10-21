package com.team3.backend.eligibility.api;

import com.team3.backend.eligibility.api.dto.EligibilitySnapshot;
import com.team3.backend.eligibility.api.dto.EligibilitySnapshotPage;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 다른 영역(추천 등)이 판정 결과를 받아 가는 유일한 진입점.
 * 호출하는 쪽은 이 인터페이스와 api.dto만 의존하고, 정책 엔진의 엔티티·서비스·레포지토리를 직접 쓰지 않는다.
 *
 * 구현 선택 (yon.eligibility.provider)
 * - local (기본): 같은 애플리케이션 안에서 직접 호출 → LocalEligibilityProvider
 * - MSA 전환 시: HTTP 등 원격 구현을 추가하고 이 값을 바꾼다. 호출하는 쪽 코드는 바뀌지 않는다
 *
 * getEligibilities·findByStatuses는 오래되거나 없는 판정 결과를 먼저 다시 판정한 뒤 돌려준다.
 * 결과마다 추천에 필요한 기회 요약(OpportunityInfo: 분류, NCS, 모집 상태, 마감일, 교육 시작일, 등록일)을 함께 담는다.
 * 판정 프로필이 없는 사용자는 BusinessException(PROFILE_NOT_FOUND).
 * findFreshStatuses·findFreshEligibility는 재판정하지 않고 저장된 최신 결과만 읽는다 (검색 목록·상세 표시용).
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

    /**
     * 저장된 최신 판정 결과의 상태만 기회 ID별로 일괄 조회한다 (예: 검색 결과 페이지의 신청 가능성 표시). DB 조회 전용, 재판정하지 않는다.
     * 최신 기준은 EligibilitySpecifications와 같다 (현재 기회 버전·판정 프로필 버전·규칙 버전이 같고 validUntil이 today보다 뒤).
     * 결과가 없거나 오래된 기회는 맵에서 빠진다. 실제 UNKNOWN 판정은 UNKNOWN으로 들어 있다.
     * 판정 프로필이 없는 사용자는 빈 맵을 돌려준다 (예외 아님). today는 한국 시간 날짜를 넘긴다.
     */
    Map<Long, EligibilityStatus> findFreshStatuses(Long userId, Collection<Long> opportunityIds, LocalDate today);

    /**
     * 저장된 최신 판정 결과 하나를 상태·사유·조건별 결과까지 조회한다 (예: 기회 상세 페이지). DB 조회 전용, 재판정하지 않는다.
     * 최신 기준은 findFreshStatuses·EligibilitySpecifications와 같다. 반환 형식은 getEligibilities와 같은 EligibilitySnapshot.
     * 결과가 없거나 오래됐으면, 또는 판정 프로필이 없는 사용자면 빈 값 (예외 아님, 검색 목록과 같이 미판정으로 다룬다).
     * today는 한국 시간 날짜를 넘긴다 (기회 요약의 모집 상태도 이 날짜로 계산한다).
     */
    Optional<EligibilitySnapshot> findFreshEligibility(Long userId, Long opportunityId, LocalDate today);
}
