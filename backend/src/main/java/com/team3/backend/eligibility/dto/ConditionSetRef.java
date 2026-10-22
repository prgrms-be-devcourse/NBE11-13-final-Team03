package com.team3.backend.eligibility.dto;

/** 판정할 조건 묶음과 그 묶음을 대표하는 기회 버전 (같은 묶음이면 어느 버전으로 판정해도 결과가 같다) */
public record ConditionSetRef(Long conditionSetId, Long versionId) {
}
