package com.team3.backend.opportunity.dto;

/** 분류 행 조회 결과. key는 조회 기준(버전 ID 또는 공고 ID)이다 */
public record OpportunityCategoryRow(Long key, String largeCategory, String mediumCategory) {
}
