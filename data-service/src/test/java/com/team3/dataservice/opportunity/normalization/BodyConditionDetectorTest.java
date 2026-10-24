package com.team3.dataservice.opportunity.normalization;

import com.team3.dataservice.opportunity.normalization.BodyConditionDetector.Detection;
import com.team3.dataservice.opportunity.normalization.BodyConditionDetector.Kind;
import com.team3.dataservice.opportunity.normalization.dto.YouthPolicyRaw;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 문장은 실데이터(온통청년, 2026-10-23 확인) 본문에서 가져왔다 */
class BodyConditionDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "부모와 별도 거주하는 19세~34세 이하의 무주택 청년 세대주에게 임차료 월 최대 20만원 지원",
            "무주택 임차인의 전세보증금반환보증 가입을 유도하여 전세사기 피해를 예방",
            "➋ (재산)무주택➌ (나이) 2026년 1월 1일 기준 만 19~39세 청년",
            "신청일 현재 본인 명의의 주택을 소유하지 않은 자"
    })
    void 주거_무주택_조건(String text) {
        assertThat(detect(text).kinds()).contains(Kind.HOUSING);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "월세지원 신청(변경)서, 소득·재산 신고서, 임대차계약(확정일자 날인)",
            "조회 : 재산세(전체), 취득세(부동산) 체크",
            "부모의 관할 구청에서 소득재산 조회 후 수급자 선정",
            "총자산가액 237백만원 이하, 개별 자동차가액 3,803만원 이하",
            "소득·자산 기준 변동 가능"
    })
    void 재산_조건(String text) {
        assertThat(detect(text).kinds()).containsExactly(Kind.PROPERTY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "선정 제외대상 · 병역 미필자, 고등학교․대학 등에 재학 중이거나 휴학 중인 자",
            "병역 의무를 마친 청년",
            "전역 예정 장병 대상"
    })
    void 병역_조건(String text) {
        assertThat(detect(text).kinds()).containsExactly(Kind.MILITARY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "인건비, 자산취득비, 해당 청년단체 사무실 임차료 등 운영비성 경비로 사용 불가",
            "① 프랜차이즈, ② 단란•유흥주점, ③ 부동산업, 금융 및 보험업 ④ 숙박업",
            "타인의 특허·실용신안 등 지적재산권 침해(우려) 아이디어",
            "전시회·박람회 참가 지원, 지식재산권 획득 등",
            "인건비, 자산성 물품, 제작·생산·양산비 지원 불가",
            "사업목적을 벗어난 재산성 물품 구입",
            "청년의 올바를 자산형성 습관을 위한 1:1 자산관리 솔루션",
            "세대구성 사유 및 일자, 세대주와의 관계, 세대주 및 세대원 전원 성명",
            "주민등록초본 (주민등록번호 뒷자리 제외, 병역 사항 공개 발급)",
            "고용위기 선제대응 지역의 위기로 인한 이‧전직 근로자를 지원",
            "국제대회 후보선수 대상자",
            "작가 박천휴 (MBC '나혼자산다' 등 출연)",
            "청년의 주거비 부담을 덜어 준다",
            "고졸 취업연계 장려금/장학금 - 제대군인 전직지원금 - 창작준비금/지원금 등",
            "[상한 연령 연장 군필자] 병적증명서, 전역증"
    })
    void 자격_조건이_아닌_표현은_찾지_않는다(String text) {
        assertThat(detect(text).isEmpty()).isTrue();
    }

    @Test
    void 종류마다_처음_찾은_줄과_필드를_근거로_남긴다() {
        YouthPolicyRaw raw = YouthPolicyRaw.builder()
                .plcyExplnCn("청년 주거 안정 지원\n무주택 청년 세대주 대상")
                .sbmsnDcmntCn("소득·재산 신고서\n재산세 미과세 증명서")
                .etcMttrCn("중복 지원 불가")
                .build();

        Detection detection = BodyConditionDetector.detect(raw);

        assertThat(detection.kinds()).containsExactly(Kind.HOUSING, Kind.PROPERTY);
        assertThat(detection.kindNames()).containsExactly("HOUSING", "PROPERTY");
        assertThat(detection.evidenceText())
                .isEqualTo("[plcyExplnCn] 무주택 청년 세대주 대상\n[sbmsnDcmntCn] 소득·재산 신고서");
        assertThat(detection.sourceFields()).containsExactly("plcyExplnCn", "sbmsnDcmntCn");
    }

    @Test
    void HTML_본문도_줄로_나눠_찾는다() {
        Detection detection = BodyConditionDetector.detect(YouthPolicyRaw.builder()
                .plcySprtCn("<p>월 20만원 지원</p><p>대상: 무주택 청년</p>").build());

        assertThat(detection.kinds()).containsExactly(Kind.HOUSING);
        assertThat(detection.evidenceText()).isEqualTo("[plcySprtCn] 대상: 무주택 청년");
    }

    @Test
    void 추가신청자격과_참여제한은_보지_않는다() {
        Detection detection = BodyConditionDetector.detect(YouthPolicyRaw.builder()
                .addAplyQlfcCndCn("무주택 세대주").ptcpPrpTrgtCn("병역 미필자 제외").build());

        assertThat(detection.isEmpty()).isTrue();
    }

    private static Detection detect(String text) {
        return BodyConditionDetector.detect(YouthPolicyRaw.builder().plcyExplnCn(text).build());
    }
}
