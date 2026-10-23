package com.team3.dataservice.opportunity.domain.entity;

/**
 * 자격조건 분류. 소득(INCOME)과 재산(PROPERTY)은 구분한다.
 * 여러 분류가 AND/OR로 결합된 조건은 COMPOSITE로 저장한다.
 * 제외조건·예외는 별도 분류가 아니라 expression 안의 NOT/예외 구조로 표현한다.
 * SPECIAL_TARGET: 장애인·한부모·기초생활수급 등, MILITARY_SERVICE: 병역(연령 상한 예외 등),
 * PARTICIPATION_HISTORY: 기존 정책 수혜·KDT 등 참여 이력, TRAINING_CARD: 국민내일배움카드
 */
public enum ConditionType {
    AGE,
    REGION,
    INCOME,
    PROPERTY,
    EMPLOYMENT,
    EDUCATION,
    MARITAL,
    HOUSING,
    SPECIALIZATION,
    SPECIAL_TARGET,
    MILITARY_SERVICE,
    PARTICIPATION_HISTORY,
    TRAINING_CARD,
    COMPOSITE,
    OTHER,
}
