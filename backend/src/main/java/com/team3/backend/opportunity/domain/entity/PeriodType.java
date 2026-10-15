package com.team3.backend.opportunity.domain.entity;

/**
 * 신청기간 유형.
 * FIXED: 날짜가 있는 신청기간(시작일 또는 종료일 하나만 있을 수 있음)
 * ALWAYS: 공식적으로 상시 신청이 확인된 경우 (aplyPrdSeCd=0057002)
 * CLOSED: 원본이 마감으로 표시한 경우 (aplyPrdSeCd=0057003, 날짜 없음)
 * UNKNOWN: 신청기간을 확인하지 못한 경우 (날짜 NULL을 상시 신청으로 해석하지 않는다)
 *
 * 신청기간 유형(aplyPrdSeCd 기준)이며 현재 신청 상태가 아니다.
 * 현재 신청 상태(UPCOMING/OPEN/ALWAYS_OPEN/CLOSED/UNKNOWN)는 조회 시점에 계산하며 저장하지 않는다.
 * 사업 운영기간(bizPrdBgngYmd/bizPrdEndYmd)은 신청기간으로 저장하지 않는다.
 */
public enum PeriodType {
    FIXED,
    ALWAYS,
    CLOSED,
    UNKNOWN,
}
