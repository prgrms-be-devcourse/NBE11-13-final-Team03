package com.team3.backend.opportunity.dto;

import java.util.List;

/**
 * 페이지 정규화 결과.
 * created: 새 기회, newVersions: 기존 기회의 새 버전, unchanged: 내용이 같아 버전을 만들지 않음,
 * skipped: 식별자·제목이 없어 정규화하지 않은 원본 (페이지의 나머지는 저장된다)
 */
public record TrainingNormalizationResult(
        int created,
        int newVersions,
        int unchanged,
        List<Skipped> skipped
) {

    public record Skipped(String trprId, String trprDegr, String reason) {
    }
}
