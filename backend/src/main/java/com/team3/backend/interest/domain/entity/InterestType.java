package com.team3.backend.interest.domain.entity;

/**
 * 추천·알림 대상과 정책을 연결하기 위한 관심 분야 코드 체계.
 *
 * CATEGORY, SUB_CATEGORY는 온통청년 정책 분류 값을 사용하고,
 * NCS는 고용24 직업훈련의 NCS 코드를 사용한다.
 */
public enum InterestType {

    CATEGORY,
    SUB_CATEGORY,
    NCS
}