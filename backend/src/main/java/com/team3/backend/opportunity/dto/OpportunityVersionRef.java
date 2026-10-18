package com.team3.backend.opportunity.dto;

/** 기회와 현재 정규화 버전 ID */
public record OpportunityVersionRef(Long opportunityId, Long versionId) {
}
