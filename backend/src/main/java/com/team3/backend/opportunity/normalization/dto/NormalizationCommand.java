package com.team3.backend.opportunity.normalization.dto;

import com.team3.backend.opportunity.normalization.official.OfficialDocument;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 정규화 요청 한 건.
 * sourceId: 수집 영역의 외부 출처 ID (온통청년), documents: 이 정책의 공식 원문 (없으면 빈 목록)
 * sourceUrl: 출처 매핑에 기록할 URL, seenAt: 이번 수집에서 확인한 시각
 */
public record NormalizationCommand(
        Long sourceId,
        YouthPolicyRaw raw,
        List<OfficialDocument> documents,
        String sourceUrl,
        LocalDateTime seenAt
) {
}
