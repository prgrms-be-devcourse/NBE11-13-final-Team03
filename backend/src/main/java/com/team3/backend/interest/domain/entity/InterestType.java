package com.team3.backend.interest.domain.entity;

/**
 * 추천·알림 대상과 정책을 연결하기 위한 관심 분야 분류 체계.
 *
 * CATEGORY:
 * - 청년정책 대분류
 * - 직업훈련의 최상위 분류
 *
 * SUB_CATEGORY:
 * - 청년정책 중분류
 * - 직업훈련 NCS 1차 분류명
 *
 * DETAIL_CATEGORY:
 * - 직업훈련 NCS 2차 분류명
 *
 * NCS:
 * - 고용24 원본 NCS 코드
 */
public enum InterestType {

    CATEGORY,
    SUB_CATEGORY,
    DETAIL_CATEGORY,
    NCS
}