package com.team3.backend.interest.dto;

/**
 * 정규화된 공고의 분류 계층을 조회하기 위한 내부 조회 결과다.
 */
public record InterestOptionRow(
        String category,
        String subCategory,
        String detailCategory
) {
}