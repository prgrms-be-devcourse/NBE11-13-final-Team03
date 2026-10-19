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
 * 대상은 K-디지털 트레이닝(trainTargetCd C0104)만이다 (팀 결정). 목록 API의 crseTracseSe=C0104 요청에도 다른 훈련유형이
 * 섞여 오므로(2026-10-19 100건 샘플: C0104 69, C0054 28, C0055C 2, C0054Y 1) 여기서 trainTargetCd로 다시 거른다.
 *
 * 1. 출처 매핑: (sourceId, "trprId:trprDegr")로 기존 Opportunity를 찾고, 없으면 새로 만든다
 * 2. 버전: 정규화 스냅샷이 현재 버전과 같으면 새 버전을 만들지 않는다. 다르면 새 버전과 훈련 상세·지역·조건·신청기간을 저장한다
 *    수집마다 바뀌는 값(수강신청 인원·만족도·취업률)은 TrainingCourseRaw에 없으므로 스냅샷에 들어가지 않는다
 * 3. 분류: 온통청년 분류 체계의 대분류 교육･직업훈련 / 중분류 직업훈련으로 고정
 * 4. 훈련지역(trngAreaCd)은 지역 사전으로 매핑해 시행 지역(IMPLEMENTATION) 행으로 저장한다. 자격 조건이 아니다
 *
 * 신청기간 (추정)
 * - 고용24 오픈API(목록 310L01, 과정/기관정보 310L02, 훈련일정 310L03)에 모집기간이 없다.
 * - 개강일 전날까지 모집하는 것으로 추정해 FIXED 신청기간 1건(시작일 없음, 종료일 = 개강일 - 1)을 VALID로 저장한다.
 *   VALID여야 신청 상태 검색(모집 중)에 잡힌다. 추정이라는 사실은 스냅샷 applicationPeriod.basis와 활용 제한 사유
 *   APPLICATION_PERIOD_ESTIMATED로 남긴다. 조기 마감은 반영하지 못한다.
 * - 개강일이 없거나 형식 오류·시작일 > 종료일이면 UNKNOWN 신청기간 1건을 저장한다 (APPLICATION_PERIOD_UNCONFIRMED).
 *
 * 훈련 자격 (trainTargetCd, 목록 API 요청 파라미터 crseTracseSe 코드표 기준)
 * - C0061·C0061S·C0061I 국민내일배움카드 과정 (현재 정규화 대상 아님. 대상을 넓힐 때를 위해 규칙만 둔다): 카드 필요 조건(TRAINING_CARD CARD_REQUIRED)
 * - C0104 K-디지털 트레이닝: 1회 한정 참여 조건(PARTICIPATION_HISTORY ONCE_ONLY KDT) + 그 밖의 자격은 원문 확인(UNSTRUCTURED)
 *   + 거주지 제한 없음(REGION NATIONAL). 훈련지역은 훈련 장소일 뿐이라 검색에서 전국 대상으로 다루기로 함 (추천팀 합의 2026-10-20)
 * - 그 밖의 훈련유형 (현재 정규화 대상 아님): 자격 규칙을 확인하지 않아 원문 확인 필요(UNSTRUCTURED, NEEDS_REVIEW)
 * 응답의 titleLink에 있는 crseTracseSe는 trainTargetCd와 다를 수 있어(예: C0104 과정의 링크가 C0061) trainTargetCd만 쓴다.
 *
 * 한 페이지는 한 트랜잭션이다. 식별자·제목이 없거나 KDT가 아닌 원본은 건너뛰고 결과에 사유를 남긴다.
 */
@Service
@RequiredArgsConstructor
public class TrainingCourseNormalizationService {

    public static final String NORMALIZATION_VERSION = "0.4.0";
    /** 국민내일배움카드 과정 (훈련유형명에 국민내일배움카드가 명시된 코드) */
    static final Set<String> CARD_REQUIRED_TARGET_CODES = Set.of("C0061", "C0061S", "C0061I");
    /** K-디지털 트레이닝. 1회만 지원한다 */
    static final String KDT_TARGET_CODE = "C0104";
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
        conditionRepository.saveAll(trainingConditions(version, raw));
        windowRepository.save(applicationWindow(version, parsed));
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
        if (!KDT_TARGET_CODE.equals(trainingTargetCode(raw))) {
            return Optional.of("K-디지털 트레이닝(C0104)이 아님: " + trainingTargetCode(raw));
        }
        return Optional.empty();
    }

    // ── 원본 값 파싱 (빈 문자열·형식 오류는 null, 형식 오류는 스냅샷 활용 제한 사유로 남긴다) ──

    record Parsed(LocalDate startDate, LocalDate endDate, boolean applicationEndEstimable, Long actualTrainingCost, Long courseFee, Integer capacity,
                  List<String> invalidFields) {

        static Parsed of(TrainingCourseRaw raw) {
            List<String> invalid = new ArrayList<>();
            LocalDate start = parseDate(raw.traStartDate(), "traStartDate", invalid);
            LocalDate end = parseDate(raw.traEndDate(), "traEndDate", invalid);
            if (start != null && end != null && start.isAfter(end)) {
                invalid.add("traStartDate>traEndDate");
            }
            return new Parsed(start, end, start != null && !invalid.contains("traStartDate>traEndDate"),
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
        if (parsed.applicationEndEstimable()) {
            Map<String, Object> applicationPeriod = new LinkedHashMap<>();
            applicationPeriod.put("endDate", estimatedApplicationEnd(parsed).toString());
            applicationPeriod.put("basis", ESTIMATED_PERIOD_BASIS);
            snapshot.put("applicationPeriod", applicationPeriod);
        }
        snapshot.put("conditions", trainingConditionSpecs(raw).stream()
                .map(spec -> Map.of("type", spec.type().name(), "expression", spec.expression()))
                .toList());

        Map<String, Object> regionMap = new LinkedHashMap<>();
        regionMap.put("status", region.status().name());
        regionMap.put("codes", region.regionCodes());
        regionMap.put("unmappedCodes", region.unmappedCodes());
        regionMap.put("validationStatus", region.validationStatus().name());
        snapshot.put("region", regionMap);

        List<String> reasons = new ArrayList<>();
        reasons.add(parsed.applicationEndEstimable() ? "APPLICATION_PERIOD_ESTIMATED" : "APPLICATION_PERIOD_UNCONFIRMED");
        if (!isCardRequired(raw)) {
            reasons.add("TRAINING_TARGET_UNSTRUCTURED");
        }
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

    /** 개강일 전날까지 모집한다는 추정 (고용24 API에 모집기간 없음) */
    static final String ESTIMATED_PERIOD_BASIS = "TRAINING_START_MINUS_ONE_DAY";

    private static LocalDate estimatedApplicationEnd(Parsed parsed) {
        return parsed.startDate().minusDays(1);
    }

    /** 클래스 주석의 신청기간 (추정) 참고 */
    private static ApplicationWindow applicationWindow(OpportunityVersion version, Parsed parsed) {
        if (!parsed.applicationEndEstimable()) {
            return ApplicationWindow.builder()
                    .version(version)
                    .periodType(PeriodType.UNKNOWN)
                    .validationStatus(ValidationStatus.UNKNOWN)
                    .build();
        }
        return ApplicationWindow.builder()
                .version(version)
                .periodType(PeriodType.FIXED)
                .endDate(estimatedApplicationEnd(parsed))
                .validationStatus(ValidationStatus.VALID)
                .build();
    }

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

    /** 훈련 자격 조건 한 건의 내용. 스냅샷과 조건 행에 같이 쓴다 */
    record TrainingConditionSpec(ConditionType type, String field, Map<String, Object> expression,
                                 ValidationStatus validationStatus, String reason) {
    }

    /** 훈련유형(trainTargetCd)별 자격 조건. 클래스 주석 참고 */
    static List<TrainingConditionSpec> trainingConditionSpecs(TrainingCourseRaw raw) {
        String code = trainingTargetCode(raw);
        if (isCardRequired(raw)) {
            return List.of(new TrainingConditionSpec(ConditionType.TRAINING_CARD, "trainingCard",
                    Map.of("status", "CARD_REQUIRED"), ValidationStatus.VALID, "훈련유형이 국민내일배움카드 과정"));
        }
        TrainingConditionSpec unstructured = new TrainingConditionSpec(ConditionType.TRAINING_CARD, "trainingTarget",
                Map.of("status", "UNSTRUCTURED"), ValidationStatus.NEEDS_REVIEW, "자격 규칙을 확인하지 않은 훈련유형 — 구조화하지 않음");
        if (KDT_TARGET_CODE.equals(code)) {
            return List.of(new TrainingConditionSpec(ConditionType.PARTICIPATION_HISTORY, "kdtParticipation",
                    Map.of("status", "ONCE_ONLY", "program", "KDT"), ValidationStatus.VALID, "K-디지털 트레이닝은 1회만 지원"),
                    new TrainingConditionSpec(ConditionType.REGION, "residenceRegion",
                            Map.of("status", RegionStatus.NATIONAL.name(), "role", RegionRole.RESIDENCE.name()),
                            ValidationStatus.VALID, "K-디지털 트레이닝은 거주지 제한 없음 (훈련지역은 훈련 장소)"),
                    unstructured);
        }
        return List.of(unstructured);
    }

    private static List<OpportunityCondition> trainingConditions(OpportunityVersion version, TrainingCourseRaw raw) {
        Map<String, Object> rawValues = new LinkedHashMap<>();
        rawValues.put("trainTargetCd", blankToNull(raw.trainTargetCd()));
        rawValues.put("trainTarget", blankToNull(raw.trainTarget()));
        return trainingConditionSpecs(raw).stream().map(spec -> {
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("sourceType", "WORK24_API");
            evidence.put("sourceFields", List.of("trainTargetCd", "trainTarget"));
            evidence.put("raw", rawValues);
            evidence.put("reason", spec.reason());
            return OpportunityCondition.builder()
                    .version(version)
                    .conditionType(spec.type())
                    .requirementType(RequirementType.REQUIRED)
                    .conditionField(spec.field())
                    .expression(spec.expression())
                    .evidence(evidence)
                    .validationStatus(spec.validationStatus())
                    .build();
        }).toList();
    }

    private static boolean isCardRequired(TrainingCourseRaw raw) {
        String code = trainingTargetCode(raw);
        return code != null && CARD_REQUIRED_TARGET_CODES.contains(code);
    }

    private static String trainingTargetCode(TrainingCourseRaw raw) {
        String code = blankToNull(raw.trainTargetCd());
        return code == null ? null : code.trim();
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
