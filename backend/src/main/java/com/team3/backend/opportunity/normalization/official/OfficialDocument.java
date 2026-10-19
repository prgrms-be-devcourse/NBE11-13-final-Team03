package com.team3.backend.opportunity.normalization.official;

import java.time.OffsetDateTime;

/**
 * 보완 입력: 수집 영역이 크롤링해 보존한 원문 한 건 (CrawledDocument 등에서 변환해 넘긴다).
 * content는 HTML 또는 일반 텍스트. contentHash는 수집 영역이 계산한 원문 해시.
 */
public record OfficialDocument(
        SourceType sourceType,
        String url,
        String content,
        String contentHash,
        OffsetDateTime fetchedAt
) {
}
