package com.team3.dataservice.opportunity.domain.entity;

/**
 * 버전별 개인화 가능 상태.
 * PERSONALIZABLE: 사용자 조건과 자동 비교 가능
 * SPLIT_REQUIRED: 서로 다른 대상·조건이 섞여 있어 분리 필요 (분리 전 자동 추천·알림 제외)
 * NON_PERSONAL: 개인 조건 비교보다 전체 안내 성격
 * UNKNOWN: 근거 부족으로 판단 불가
 */
public enum PersonalizationStatus {
    PERSONALIZABLE,
    SPLIT_REQUIRED,
    NON_PERSONAL,
    UNKNOWN,
}
