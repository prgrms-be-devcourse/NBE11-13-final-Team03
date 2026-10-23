package com.team3.dataservice.opportunity.dto;

/** 기회와 현재 정규화 버전, 그 버전의 조건 묶음 ID (조건 묶음이 아직 없으면 null) */
public record OpportunitySetRef(Long opportunityId, Long versionId, Long conditionSetId) {
}
