package com.team3.backend.collection.service;

/**
 * 정제 소비자가 PENDING 요청을 조회하도록 깨우는 신호.
 * 알림 수와 요청 수는 일치하지 않으며 선점·처리·상태 변경은 소비자가 담당한다.
 */
public record NormalizationRequestsAvailable() {
}
