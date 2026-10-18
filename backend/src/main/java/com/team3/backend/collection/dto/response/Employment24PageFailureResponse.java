package com.team3.backend.collection.dto.response;

import com.team3.backend.collection.dto.external.Employment24SearchRequest;

/** 실패한 NCS·조회 기간·페이지 조건을 함께 남겨 동일 조건으로 재수집할 수 있게 한다. */
public record Employment24PageFailureResponse(
        Employment24SearchRequest request,
        String errorCode,
        String errorMessage,
        boolean retryable
) {}
