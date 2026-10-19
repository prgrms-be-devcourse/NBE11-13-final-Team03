package com.team3.backend.opportunity.service;

import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.repository.*;
import com.team3.backend.opportunity.dto.TrainingCourseRaw;
import com.team3.backend.opportunity.dto.TrainingNormalizationCommand;
import com.team3.backend.opportunity.dto.TrainingNormalizationResult;
import com.team3.backend.opportunity.normalization.RegionNormalization;
import com.team3.backend.opportunity.normalization.RegionNormalizer;
import com.team3.backend.opportunity.normalization.RegionStatus;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.directory.RegionIndex;
import com.team3.backend.region.domain.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 고용24 훈련과정 목록 한 페이지를 정규화해 저장한다. Opportunity 하나 = 훈련과정 한 회차.
 *
 * 1. 출처 매핑: (sourceId, "trprId:trprDegr")로 기존 Opportunity를 찾고, 없으면 새로 만든다
 * 2. 버전: 정규화 스냅샷이 현재 버전과 같으면 새 버전을 만들지 않는다. 다르면 새 버전과 훈련 상세·지역·조건·신청기간을 저장한다
 *    수집마다 바뀌는 값(수강신청 인원·만족도·취업률)은 TrainingCourseRaw에 없으므로 스냅샷에 들어가지 않는다
 * 3. 분류: 온통청년 분류 체계의 대분류 교육･직업훈련 / 중분류 직업훈련으로 고정
 * 4. 훈련지역(trngAreaCd)은 지역 사전으로 매핑해 시행 지역(IMPLEMENTATION) 행으로 저장한다. 자격 조건이 아니다
 *
 * 아직 원본에 없는 것 (명세 대기)
 * - 모집기간: 목록 API에 없어 UNKNOWN 신청기간 1건을 저장한다 (상세 API 반영 시 교체)
 * - 훈련 자격: 훈련구분 코드표가 없어 훈련대상 원문을 구조화하지 않은 조건(NEEDS_REVIEW)으로 남긴다 → 판정은 원문 확인 필요
 *
 * 한 페이지는 한 트랜잭션이다. 식별자·제목이 없는 원본은 건너뛰고 결과에 사유를 남긴다.
 */
@Service
@RequiredArgsConstructor
public class TrainingCourseNormalizationService {

    public static final String NORMALIZATION_VERSION = "0.1.0";
    /** 온통청년 대분류 (가운데 점은 원본과 같은 반각 ･ U+FF65) */
    public static final String CATEGORY = "교육･직업훈련";
    public static final String SUB_CATEGORY = "직업훈련";

    private final RegionNormalizer regionNormalizer;
    private final RegionDirectory regionDirectory;
    private final OpportunityRepository opportunityRepository;
    private final OpportunitySourceRepository sourceRepository;
    private final OpportunityVersionRepository versionRepository;
    private final OpportunityConditionRepository conditionRepository;
    private final ApplicationWindowRepository windowRepository;
    private final OpportunityRegionRepository opportunityRegionRepository;
    private final TrainingCourseDetailRepository trainingDetailRepository;
    private final RegionRepository regionRepository;

    @Transactional
    public TrainingNormalizationResult normalizePage(TrainingNormalizationCommand command) {
        Objects.requireNonNull(command.sourceId(), "sourceId");
        Objects.requireNonNull(command.seenAt(), "seenAt");
        RegionIndex index = regionDirectory.index();
        int created = 0, newVersions = 0, unchanged = 0;
        List<TrainingNormalizationResult.Skipped> skipped = new ArrayList<>();

        for (TrainingCourseRaw raw : command.courses() == null ? List.<TrainingCourseRaw>of() : command.courses()) {
            Optional<String> invalid = invalidReason(raw);
            if (invalid.isPresent()) {
                skipped.add(new TrainingNormalizationResult.Skipped(
                        raw == null ? null : raw.trprId(), raw == null ? null : raw.trprDegr(), invalid.get()));
                continue;
            }
            switch (normalize(raw, command.sourceId(), command.seenAt(), index)) {
                case CREATED -> created++;
                case NEW_VERSION -> newVersions++;
                case UNCHANGED -> unchanged++;
            }
        }
        return new TrainingNormalizationResult(created, newVersions, unchanged, skipped);
    }

    private enum Outcome { CREATED, NEW_VERSION, UNCHANGED }

    private Outcome normalize(TrainingCourseRaw raw, Long sourceId, LocalDateTime seenAt, RegionIndex index) {
        String title = raw.title().trim();
        String detailUrl = blankToNull(raw.titleLink());
        RegionNormalization region = regionNormalizer.normalize(raw.trngAreaCd(), index);
        Parsed parsed = Parsed.of(raw);
        Map<String, Object> snapshot = snapshot(raw, parsed, region);

        String externalId = externalId(raw);
        Optional<OpportunitySource> existing = sourceRepository.findBySourceIdAndExternalId(sourceId, externalId);
        Opportunity opportunity;
        if (existing.isPresent()) {
            opportunity = existing.get().getOpportunity();
            existing.get().markSeen(seenAt, detailUrl);
        } else {
            opportunity = opportunityRepository.save(Opportunity.builder()
                    .opportunityType(OpportunityType.VOCATIONAL_TRAINING)
                    .title(title)
                    .category(CATEGORY)
                    .subCategory(SUB_CATEGORY)
                    .detailUrl(detailUrl)
                    .build());
            sourceRepository.save(OpportunitySource.builder()
                    .opportunity(opportunity)
                    .sourceId(sourceId)
                    .externalId(externalId)
                    .sourceUrl(detailUrl)
                    .firstSeenAt(seenAt)
                    .lastSeenAt(seenAt)
                    .build());
        }

        OpportunityVersion current = opportunity.getCurrentVersion();
        if (current != null && snapshot.equals(current.getNormalizedSnapshot())) {
            return Outcome.UNCHANGED;
        }

        OpportunityVersion version = versionRepository.save(OpportunityVersion.builder()
                .opportunity(opportunity)
                .versionNo(current == null ? 1 : current.getVersionNo() + 1)
                .normalizedSnapshot(snapshot)
                .personalizationStatus(PersonalizationStatus.UNKNOWN)
                .normalizationVersion(NORMALIZATION_VERSION)
                .build());
        trainingDetailRepository.save(detail(version, raw, parsed));
        conditionRepository.save(trainingTargetCondition(version, raw));
        windowRepository.save(ApplicationWindow.builder()
                .version(version)
                .periodType(PeriodType.UNKNOWN)
                .validationStatus(ValidationStatus.UNKNOWN)
                .build());
        opportunityRegionRepository.saveAll(regions(version, region));

        opportunity.changeCurrentVersion(version);
        opportunity.updateSummary(title, CATEGORY, SUB_CATEGORY, detailUrl);
        return existing.isEmpty() ? Outcome.CREATED : Outcome.NEW_VERSION;
    }

    static String externalId(TrainingCourseRaw raw) {
        return raw.trprId().trim() + ":" + Integer.parseInt(raw.trprDegr().trim());
    }

    static Optional<String> invalidReason(TrainingCourseRaw raw) {
        if (raw == null) {
            return Optional.of("원본이 없음");
        }
        if (raw.trprId() == null || raw.trprId().isBlank()) {
            return Optional.of("훈련과정ID(trprId) 없음");
        }
        if (raw.title() == null || raw.title().isBlank()) {
            return Optional.of("제목(title) 없음");
        }
        if (parseInt(raw.trprDegr()) == null) {
            return Optional.of("회차(trprDegr)가 숫자가 아님: " + raw.trprDegr());
        }
        return Optional.empty();
    }

    // ── 원본 값 파싱 (빈 문자열·형식 오류는 null, 형식 오류는 스냅샷 활용 제한 사유로 남긴다) ──

    record Parsed(LocalDate startDate, LocalDate endDate, Long actualTrainingCost, Long courseFee, Integer capacity,
                  List<String> invalidFields) {

        static Parsed of(TrainingCourseRaw raw) {
            List<String> invalid = new ArrayList<>();
            LocalDate start = parseDate(raw.traStartDate(), "traStartDate", invalid);
            LocalDate end = parseDate(raw.traEndDate(), "traEndDate", invalid);
            if (start != null && end != null && start.isAfter(end)) {
                invalid.add("traStartDate>traEndDate");
            }
            return new Parsed(start, end,
                    parseLong(raw.realMan(), "realMan", invalid),
                    parseLong(raw.courseMan(), "courseMan", invalid),
                    parseIntField(raw.yardMan(), "yardMan", invalid),
                    List.copyOf(invalid));
        }
    }

    private static LocalDate parseDate(String value, String field, List<String> invalid) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            invalid.add(field);
            return null;
        }
    }

    private static Long parseLong(String value, String field, List<String> invalid) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            invalid.add(field);
            return null;
        }
    }

    private static Integer parseIntField(String value, String field, List<String> invalid) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        Integer parsed = parseInt(v);
        if (parsed == null) {
            invalid.add(field);
        }
        return parsed;
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── 스냅샷 (String, Integer, Long, Boolean, List, Map만 사용: DB(jsonb)에서 읽어도 equals가 성립하도록) ──

    static Map<String, Object> snapshot(TrainingCourseRaw raw, Parsed parsed, RegionNormalization region) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", raw.title().trim());
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("large", CATEGORY);
        category.put("medium", SUB_CATEGORY);
        snapshot.put("category", category);

        Map<String, Object> training = new LinkedHashMap<>();
        training.put("courseId", raw.trprId().trim());
        training.put("courseRound", parseInt(raw.trprDegr()));
        putIfNotNull(training, "institutionName", blankToNull(raw.subTitle()));
        putIfNotNull(training, "institutionCode", blankToNull(raw.instCd()));
        putIfNotNull(training, "institutionCustomerId", blankToNull(raw.trainstCstId()));
        putIfNotNull(training, "startDate", parsed.startDate() == null ? null : parsed.startDate().toString());
        putIfNotNull(training, "endDate", parsed.endDate() == null ? null : parsed.endDate().toString());
        putIfNotNull(training, "actualTrainingCost", jsonLong(parsed.actualTrainingCost()));
        putIfNotNull(training, "courseFee", jsonLong(parsed.courseFee()));
        putIfNotNull(training, "capacity", parsed.capacity());
        putIfNotNull(training, "ncsCode", blankToNull(raw.ncsCd()));
        putIfNotNull(training, "trainingTargetCode", blankToNull(raw.trainTargetCd()));
        putIfNotNull(training, "trainingTargetName", blankToNull(raw.trainTarget()));
        putIfNotNull(training, "weekendTypeCode", blankToNull(raw.wkendSe()));
        putIfNotNull(training, "address", blankToNull(raw.address()));
        putIfNotNull(training, "telNo", blankToNull(raw.telNo()));
        snapshot.put("training", training);

        Map<String, Object> regionMap = new LinkedHashMap<>();
        regionMap.put("status", region.status().name());
        regionMap.put("codes", region.regionCodes());
        regionMap.put("unmappedCodes", region.unmappedCodes());
        regionMap.put("validationStatus", region.validationStatus().name());
        snapshot.put("region", regionMap);

        List<String> reasons = new ArrayList<>();
        reasons.add("APPLICATION_PERIOD_UNCONFIRMED");
        reasons.add("TRAINING_TARGET_UNSTRUCTURED");
        if (region.validationStatus() != ValidationStatus.VALID) {
            reasons.add("REGION_" + region.validationStatus().name());
        }
        parsed.invalidFields().forEach(f -> reasons.add("INVALID_" + f));
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("trackingEligible", true);
        usage.put("exclusionReasons", reasons);
        snapshot.put("usage", usage);
        return snapshot;
    }

    /** JSON 왕복 후에도 equals가 성립하도록 int 범위면 Integer로 */
    private static Object jsonLong(Long value) {
        if (value == null) {
            return null;
        }
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? (Object) value.intValue() : (Object) value;
    }

    // ── 엔티티 변환 ──

    private static TrainingCourseDetail detail(OpportunityVersion version, TrainingCourseRaw raw, Parsed parsed) {
        return TrainingCourseDetail.builder()
                .version(version)
                .courseId(raw.trprId().trim())
                .courseRound(parseInt(raw.trprDegr()))
                .institutionName(blankToNull(raw.subTitle()))
                .institutionCode(blankToNull(raw.instCd()))
                .institutionCustomerId(blankToNull(raw.trainstCstId()))
                .trainingStartDate(parsed.startDate())
                .trainingEndDate(parsed.endDate())
                .actualTrainingCost(parsed.actualTrainingCost())
                .courseFee(parsed.courseFee())
                .capacity(parsed.capacity())
                .ncsCode(blankToNull(raw.ncsCd()))
                .trainingTargetCode(blankToNull(raw.trainTargetCd()))
                .trainingTargetName(blankToNull(raw.trainTarget()))
                .weekendTypeCode(blankToNull(raw.wkendSe()))
                .address(blankToNull(raw.address()))
                .telNo(blankToNull(raw.telNo()))
                .build();
    }

    /** 훈련구분 코드표가 없어 훈련대상을 구조화하지 않은 필수 조건으로 남긴다 → 판정은 원문 확인 필요(CHECK_REQUIRED) */
    private static OpportunityCondition trainingTargetCondition(OpportunityVersion version, TrainingCourseRaw raw) {
        Map<String, Object> rawValues = new LinkedHashMap<>();
        rawValues.put("trainTargetCd", blankToNull(raw.trainTargetCd()));
        rawValues.put("trainTarget", blankToNull(raw.trainTarget()));

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("sourceType", "WORK24_API");
        evidence.put("sourceFields", List.of("trainTargetCd", "trainTarget"));
        evidence.put("raw", rawValues);
        evidence.put("reason", "훈련구분 코드표 확인 전 — 구조화하지 않음");

        return OpportunityCondition.builder()
                .version(version)
                .conditionType(ConditionType.TRAINING_CARD)
                .requirementType(RequirementType.REQUIRED)
                .conditionField("trainingTarget")
                .expression(Map.of("status", "UNSTRUCTURED"))
                .evidence(evidence)
                .validationStatus(ValidationStatus.NEEDS_REVIEW)
                .build();
    }

    /** SPECIFIC일 때만 시행 지역 행을 만든다 */
    private List<OpportunityRegion> regions(OpportunityVersion version, RegionNormalization region) {
        if (region.status() != RegionStatus.SPECIFIC) {
            return List.of();
        }
        return region.regionCodes().stream()
                .map(code -> OpportunityRegion.builder()
                        .version(version)
                        .region(regionRepository.getReferenceById(code))
                        .regionRole(RegionRole.IMPLEMENTATION)
                        .build())
                .toList();
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
