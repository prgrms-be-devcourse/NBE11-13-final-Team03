package com.team3.dataservice.opportunity.normalization.dto;

import lombok.Builder;

/**
 * 정규화 엔진 입력: 온통청년 원본 한 건.
 * 필드명은 실제 API 필드명을 그대로 쓰고, 값은 원본 문자열 그대로 받는다 (숫자도 문자열, 빈 문자열 가능).
 * 수집 영역이 Raw를 어떤 방식으로 넘기든(조회, 이벤트 등) 이 record로 바꿔 넘기면 된다.
 *
 * 필드 의미 (공식 출력 항목표 기준)
 * - ptcpPrpTrgtCn: 참여제한대상내용 → 제외 조건. 자격(연령 등) 근거로 쓰지 않는다
 * - addAplyQlfcCndCn: 추가신청자격조건내용
 * - zipCd: 정책거주지역코드 → 거주지(RESIDENCE) 조건
 * - mrgSttsCd 결혼상태, jobCd 취업요건, schoolCd 학력요건, plcyMajorCd 전공요건, sBizCd 특화요건, earnCndSeCd 소득조건구분
 * - sBizCd: 실제 JSON 키는 sbizCd로 올 수 있어 수집 DTO에서 둘 다 받아야 함
 * - 상세 정보 (YouthPolicyDetail): plcySprtCn 지원내용, plcyKywdNm 정책키워드, sprvsnInstCdNm 주관기관명,
 *   operInstCdNm 운영기관명, plcyAplyMthdCn 신청방법, srngMthdCn 심사방법, sbmsnDcmntCn 제출서류,
 *   aplyUrlAddr 신청URL, refUrlAddr1·refUrlAddr2 참고URL, bizPrdBgngYmd·bizPrdEndYmd·bizPrdEtcCn 사업기간,
 *   sprtSclCnt 지원규모, frstRegDt 최초등록일시. 필드명은 수집 원본(2026-10-21, 4,743건)의 키로 확인함
 */
@Builder
public record YouthPolicyRaw(
        String plcyNo,
        String plcyNm,
        String sprtTrgtMinAge,
        String sprtTrgtMaxAge,
        String sprtTrgtAgeLmtYn,
        String ptcpPrpTrgtCn,
        String addAplyQlfcCndCn,
        String aplyPrdSeCd,
        String aplyYmd,
        String plcyExplnCn,
        String earnEtcCn,
        String etcMttrCn,
        String lclsfNm,
        String mclsfNm,
        String zipCd,
        String mrgSttsCd,
        String jobCd,
        String schoolCd,
        String plcyMajorCd,
        String sBizCd,
        String earnCndSeCd,
        String earnMinAmt,
        String earnMaxAmt,
        String plcySprtCn,
        String plcyKywdNm,
        String sprvsnInstCdNm,
        String operInstCdNm,
        String plcyAplyMthdCn,
        String srngMthdCn,
        String sbmsnDcmntCn,
        String aplyUrlAddr,
        String refUrlAddr1,
        String refUrlAddr2,
        String bizPrdBgngYmd,
        String bizPrdEndYmd,
        String bizPrdEtcCn,
        String sprtSclCnt,
        String frstRegDt
) {

    public YouthPolicyRaw(String plcyNo, String plcyNm, String sprtTrgtMinAge, String sprtTrgtMaxAge,
                          String sprtTrgtAgeLmtYn, String ptcpPrpTrgtCn, String addAplyQlfcCndCn,
                          String aplyPrdSeCd, String aplyYmd) {
        this(plcyNo, plcyNm, sprtTrgtMinAge, sprtTrgtMaxAge, sprtTrgtAgeLmtYn, ptcpPrpTrgtCn, addAplyQlfcCndCn,
                aplyPrdSeCd, aplyYmd, null, null, null);
    }

    public YouthPolicyRaw(String plcyNo, String plcyNm, String sprtTrgtMinAge, String sprtTrgtMaxAge,
                          String sprtTrgtAgeLmtYn, String ptcpPrpTrgtCn, String addAplyQlfcCndCn,
                          String aplyPrdSeCd, String aplyYmd, String plcyExplnCn, String earnEtcCn, String etcMttrCn) {
        this(plcyNo, plcyNm, sprtTrgtMinAge, sprtTrgtMaxAge, sprtTrgtAgeLmtYn, ptcpPrpTrgtCn, addAplyQlfcCndCn,
                aplyPrdSeCd, aplyYmd, plcyExplnCn, earnEtcCn, etcMttrCn,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
