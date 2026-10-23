package com.team3.dataservice.opportunity.normalization.official;

/**
 * 근거 출처 유형과 기본 신뢰도 (개선된 정제 기준 9절).
 * DOCUMENT_TEXT(첨부 문서)는 출처에 따라 다르므로 기본값 MEDIUM으로 두고, 시행기관 공고 첨부라면 OFFICIAL_NOTICE로 넘긴다.
 */
public enum SourceType {
    OFFICIAL_NOTICE(Confidence.HIGH),
    OFFICIAL_PAGE(Confidence.HIGH),
    YOUTH_CENTER_DETAIL(Confidence.MEDIUM),
    YOUTH_CENTER_API(Confidence.MEDIUM),
    /** API 자유텍스트 필드(addAplyQlfcCndCn 등)에서 찾은 근거. 외부 공식 원문과 구분한다 */
    API_TEXT(Confidence.MEDIUM),
    DOCUMENT_TEXT(Confidence.MEDIUM),
    MANUAL_INFERENCE(Confidence.LOW);

    private final Confidence confidence;

    SourceType(Confidence confidence) {
        this.confidence = confidence;
    }

    public Confidence confidence() {
        return confidence;
    }
}
