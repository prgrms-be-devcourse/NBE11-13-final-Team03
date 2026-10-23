package com.team3.dataservice.opportunity.normalization.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 고용24 훈련과정 목록 한 페이지의 정규화 요청. 수집 파트의 전달 방식(배치·메시지큐·DB 폴링)과 무관하게
 * 어댑터가 페이지마다 이 형태로 넘긴다.
 * sourceId: 수집 영역의 외부 출처 ID (고용24), seenAt: 이번 수집에서 확인한 시각
 */
public record TrainingNormalizationCommand(
        Long sourceId,
        List<TrainingCourseRaw> courses,
        LocalDateTime seenAt
) {
}
