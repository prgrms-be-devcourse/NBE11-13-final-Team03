package com.team3.backend.opportunity.normalization.dto;

import lombok.Builder;

/**
 * 정규화 엔진 입력: 고용24 훈련과정 목록(srchList) 원본 한 건.
 * 필드명은 실제 응답 필드명을 그대로 쓰고, 값은 원본 문자열 그대로 받는다 (숫자도 문자열, 빈 문자열 가능).
 * 고용24 훈련과정 목록 API 출력 명세와 응답 샘플(2026-10-16) 기준. 모집기간 항목은 목록 응답에 없다 (상세 API).
 * 수강신청 인원·만족도·취업률 등 수집마다 바뀌는 항목은 정규화 버전에 쓰지 않으므로 받지 않는다.
 *
 * - trprId 훈련과정ID, trprDegr 훈련과정 순차(회차) → 외부 식별자 "trprId:trprDegr"
 * - traStartDate, traEndDate: 훈련시작·종료일자 (샘플 형식 yyyy-MM-dd)
 * - realMan 실제 훈련비, courseMan 수강비, yardMan 정원
 * - trngAreaCd: 지역코드(중분류), 샘플상 5자리 시군구 코드
 * - trainTargetCd 훈련구분, trainTarget 훈련대상. titleLink의 crseTracseSe와 다를 수 있다 (예: C0054Y vs C0061)
 * - wkendSe 주말/주중 구분: 코드표 확인 필요 (샘플 2, 3, 9)
 */
@Builder
public record TrainingCourseRaw(
        String trprId,
        String trprDegr,
        String title,
        String subTitle,
        String instCd,
        String trainstCstId,
        String traStartDate,
        String traEndDate,
        String realMan,
        String courseMan,
        String yardMan,
        String ncsCd,
        String trainTarget,
        String trainTargetCd,
        String wkendSe,
        String trngAreaCd,
        String address,
        String telNo,
        String titleLink
) {
}
