package com.team3.backend.opportunity.ingest;

import com.team3.backend.opportunity.normalization.dto.NormalizationCommand;
import com.team3.backend.opportunity.normalization.dto.TrainingCourseRaw;
import com.team3.backend.opportunity.normalization.dto.TrainingNormalizationCommand;
import com.team3.backend.opportunity.normalization.dto.TrainingNormalizationResult;
import com.team3.backend.opportunity.normalization.dto.YouthPolicyRaw;
import com.team3.backend.opportunity.service.TrainingCourseNormalizationService;
import com.team3.backend.opportunity.service.YouthPolicyNormalizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 수집 원본 한 건(payload JSON)을 출처별 정규화 입력으로 바꿔 정규화 서비스에 넘긴다.
 * 출처 구분은 external_source.source_code (수집 영역 CollectionSource 이름: YOUTH_POLICY, EMPLOYMENT24).
 *
 * 결과: NORMALIZED(정규화함, 내용이 같아 새 버전을 안 만든 경우 포함), INVALID(원본 데이터 문제 — 다시 해도 같음).
 * 그 밖의 예외는 그대로 던진다 (일시적일 수 있어 다음 대조 때 다시 시도).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RawRecordNormalizer {

    public static final String YOUTH_POLICY = "YOUTH_POLICY";
    public static final String EMPLOYMENT24 = "EMPLOYMENT24";

    public enum Outcome { NORMALIZED, INVALID }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 원본에는 정규화에 안 쓰는 필드(수강신청 인원 등)도 있으므로 모르는 필드는 무시한다 */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final TrainingCourseNormalizationService trainingService;
    private final YouthPolicyNormalizationService youthPolicyService;

    public Outcome normalize(RawRecordRow row) {
        LocalDateTime seenAt = row.lastSeenAt().atZoneSameInstant(KST).toLocalDateTime();
        JsonNode payload;
        try {
            payload = MAPPER.readTree(row.payload());
        } catch (JacksonException e) {
            return invalid(row, "payload JSON 아님");
        }
        return switch (row.sourceCode()) {
            case EMPLOYMENT24 -> training(row, payload, seenAt);
            case YOUTH_POLICY -> youthPolicy(row, payload, seenAt);
            default -> invalid(row, "알 수 없는 출처 " + row.sourceCode());
        };
    }

    private Outcome training(RawRecordRow row, JsonNode payload, LocalDateTime seenAt) {
        TrainingCourseRaw raw;
        try {
            raw = MAPPER.treeToValue(payload, TrainingCourseRaw.class);
        } catch (JacksonException e) {
            return invalid(row, "TrainingCourseRaw 변환 실패: " + e.getOriginalMessage());
        }
        TrainingNormalizationResult result = trainingService.normalizePage(
                new TrainingNormalizationCommand(row.sourceId(), List.of(raw), seenAt));
        if (!result.skipped().isEmpty()) {
            return invalid(row, result.skipped().getFirst().reason());
        }
        return Outcome.NORMALIZED;
    }

    private Outcome youthPolicy(RawRecordRow row, JsonNode payload, LocalDateTime seenAt) {
        YouthPolicyRaw raw;
        try {
            raw = MAPPER.treeToValue(withSBizCd(payload), YouthPolicyRaw.class);
        } catch (JacksonException e) {
            return invalid(row, "YouthPolicyRaw 변환 실패: " + e.getOriginalMessage());
        }
        try {
            youthPolicyService.normalize(new NormalizationCommand(row.sourceId(), raw, List.of(), null, seenAt));
        } catch (IllegalArgumentException e) {
            return invalid(row, e.getMessage());
        }
        return Outcome.NORMALIZED;
    }

    /** 특화요건 코드는 실제 JSON 키가 sbizCd로 오기도 한다 (YouthPolicyRaw 주석 참고) */
    private static JsonNode withSBizCd(JsonNode payload) {
        if (payload instanceof ObjectNode object && !object.has("sBizCd") && object.has("sbizCd")) {
            ObjectNode copy = object.deepCopy();
            copy.set("sBizCd", object.get("sbizCd"));
            return copy;
        }
        return payload;
    }

    private static Outcome invalid(RawRecordRow row, String reason) {
        log.warn("Raw record skipped as invalid: rawRecordId={}, source={}, externalId={}, reason={}",
                row.rawRecordId(), row.sourceCode(), row.externalId(), reason);
        return Outcome.INVALID;
    }
}
