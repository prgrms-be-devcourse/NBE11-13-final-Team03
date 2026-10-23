package com.team3.backend.opportunity.support;

import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.region.domain.entity.Region;
import jakarta.persistence.EntityManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

/**
 * 정규화된 공고 테스트 데이터. 정규화는 data-service에 있으므로 메인 서비스 테스트는 정규화 결과와 같은 행을 직접 만든다.
 *
 * 조건 행의 모양(조건 유형·필드·expression·검증 상태)은 data-service 정규화가 만드는 값과 같게 둔다
 * (YouthPolicyNormalizationService, TrainingCourseNormalizationService). 근거(evidence)는 판정에 쓰지 않으므로 원문만 남긴다.
 * 값을 정하지 않은 항목은 정규화에서 "원본 값 없음"일 때의 결과다 (연령·지역·코드·소득 UNKNOWN, 신청기간 UNKNOWN).
 *
 * 정규화처럼 같은 출처·외부 ID로 다시 저장하면 내용이 같을 때는 현재 버전을 그대로 두고, 다르면 새 버전을 만든다.
 * 조건 묶음은 data-service와 같은 키 규칙(ConditionSetKeys)으로 찾고, 없으면 만든다.
 * 호출하는 쪽의 트랜잭션 안에서 실행한다.
 */
public final class OpportunityFixtures {

    public static final long POLICY_SOURCE_ID = 1L;
    public static final long TRAINING_SOURCE_ID = 2L;
    /** data-service 정규화와 같은 분류 값 (가운데 점은 반각 ･ U+FF65) */
    public static final String TRAINING_CATEGORY = "교육･직업훈련";
    public static final String TRAINING_SUB_CATEGORY = "직업훈련";

    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private OpportunityFixtures() {
    }

    /** 저장 결과. 정규화 결과(NormalizationResult)와 같은 의미 */
    public record Saved(Long opportunityId, Long versionId, int versionNo, boolean created, boolean newVersion) {
    }

    public static YouthPolicy youthPolicy(String externalId, String title) {
        return new YouthPolicy(externalId, title);
    }

    public static Training training(String courseId, int round, String title) {
        return new Training(courseId, round, title);
    }

    // ── 청년정책 ──

    /** 온통청년 정책 한 건의 정규화 결과 */
    public static final class YouthPolicy {

        /** 코드 조건 저장 순서와 조건 필드명 (정규화와 같음) */
        private static final Map<ConditionType, String> CODE_FIELDS = new LinkedHashMap<>();

        static {
            CODE_FIELDS.put(ConditionType.MARITAL, "maritalStatus");
            CODE_FIELDS.put(ConditionType.EMPLOYMENT, "employmentStatus");
            CODE_FIELDS.put(ConditionType.EDUCATION, "education");
            CODE_FIELDS.put(ConditionType.SPECIALIZATION, "major");
            CODE_FIELDS.put(ConditionType.SPECIAL_TARGET, "specialTarget");
        }

        private final String externalId;
        private final String title;
        private long sourceId = POLICY_SOURCE_ID;
        private String category;
        private String subCategory;
        private Map<String, Object> ageExpression = Map.of("status", "UNKNOWN");
        private ValidationStatus ageValidation = ValidationStatus.UNKNOWN;
        private String regionStatus = "UNKNOWN";
        private ValidationStatus regionValidation = ValidationStatus.UNKNOWN;
        private List<String> residenceCodes = List.of();
        private final Map<ConditionType, Code> codes = new LinkedHashMap<>();
        private Map<String, Object> incomeExpression = income("UNKNOWN");
        private ValidationStatus incomeValidation = ValidationStatus.UNKNOWN;
        private String additionalQualification;
        private String participationRestriction;
        private final List<Window> windows = new ArrayList<>();

        private record Code(String status, List<String> values, ValidationStatus validation) {
        }

        private YouthPolicy(String externalId, String title) {
            this.externalId = externalId;
            this.title = title;
            CODE_FIELDS.keySet().forEach(type -> codes.put(type, new Code("UNKNOWN", List.of(), ValidationStatus.UNKNOWN)));
        }

        public YouthPolicy source(long sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        public YouthPolicy category(String category, String subCategory) {
            this.category = category;
            this.subCategory = subCategory;
            return this;
        }

        /** 최소·최대 연령 (sprtTrgtMinAge·MaxAge, 제한 Y) */
        public YouthPolicy age(int min, int max) {
            return age(Map.of("status", "RANGE", "min", min, "max", max), ValidationStatus.VALID);
        }

        /** 연령을 정하지 못함. 예: 0/0/Y·19/34/N은 DATA_CONFLICT, 값 없음은 UNKNOWN */
        public YouthPolicy ageUnknown(ValidationStatus validation) {
            return age(Map.of("status", "UNKNOWN"), validation);
        }

        public YouthPolicy age(Map<String, Object> expression, ValidationStatus validation) {
            this.ageExpression = expression;
            this.ageValidation = validation;
            return this;
        }

        /** 거주지역 제한 (지역 사전으로 매핑·압축한 뒤의 10자리 코드) */
        public YouthPolicy residence(String... regionCodes) {
            this.regionStatus = "SPECIFIC";
            this.regionValidation = ValidationStatus.VALID;
            this.residenceCodes = List.of(regionCodes);
            return this;
        }

        public YouthPolicy national() {
            this.regionStatus = "NATIONAL";
            this.regionValidation = ValidationStatus.VALID;
            this.residenceCodes = List.of();
            return this;
        }

        /** 지역을 정하지 못함. 예: 사전에 없는 코드만 있으면 NEEDS_REVIEW, 값 없음은 UNKNOWN */
        public YouthPolicy regionUnknown(ValidationStatus validation) {
            this.regionStatus = "UNKNOWN";
            this.regionValidation = validation;
            this.residenceCodes = List.of();
            return this;
        }

        /** 코드 조건 (혼인·취업·학력·전공·특화). 값은 코드표의 내부 값 */
        public YouthPolicy code(ConditionType type, String... values) {
            return code(type, "SPECIFIC", ValidationStatus.VALID, values);
        }

        public YouthPolicy codeNoLimit(ConditionType type) {
            return code(type, "NO_LIMIT", ValidationStatus.VALID);
        }

        /** 다섯 코드 조건 모두 제한 없음 */
        public YouthPolicy codesNoLimit() {
            CODE_FIELDS.keySet().forEach(this::codeNoLimit);
            return this;
        }

        public YouthPolicy code(ConditionType type, String status, ValidationStatus validation, String... values) {
            if (!CODE_FIELDS.containsKey(type)) {
                throw new IllegalArgumentException("코드 조건이 아님: " + type);
            }
            codes.put(type, new Code(status, new TreeSet<>(List.of(values)).stream().toList(), validation));
            return this;
        }

        public YouthPolicy incomeNoLimit() {
            return income(income("NO_LIMIT"), ValidationStatus.VALID);
        }

        /** 소득 expression (status·type·min·max·minInclusive·maxInclusive·period·subject·unitConfirmed) */
        public YouthPolicy income(Map<String, Object> expression, ValidationStatus validation) {
            this.incomeExpression = expression;
            this.incomeValidation = validation;
            return this;
        }

        /** 추가 신청 자격 원문 (addAplyQlfcCndCn) → 원문 확인이 필요한 필수조건 */
        public YouthPolicy additionalQualification(String text) {
            this.additionalQualification = text;
            return this;
        }

        /** 참여 제한 대상 원문 (ptcpPrpTrgtCn) → 원문 확인이 필요한 제외조건 */
        public YouthPolicy participationRestriction(String text) {
            this.participationRestriction = text;
            return this;
        }

        /** 고정 신청기간 (0057001). 원문 표기는 "yyyyMMdd ~ yyyyMMdd" */
        public YouthPolicy fixedPeriod(LocalDate start, LocalDate end) {
            return period(PeriodType.FIXED, start, end, ValidationStatus.VALID,
                    start.format(BASIC_DATE) + " ~ " + end.format(BASIC_DATE));
        }

        /** 상시 신청 (0057002) */
        public YouthPolicy alwaysOpen() {
            return period(PeriodType.ALWAYS, null, null, ValidationStatus.VALID, null);
        }

        public YouthPolicy period(PeriodType type, LocalDate start, LocalDate end, ValidationStatus validation,
                                  String periodText) {
            windows.add(new Window(type, start, end, validation, periodText));
            return this;
        }

        public Saved save(EntityManager em) {
            List<Window> periods = windows.isEmpty()
                    ? List.of(new Window(PeriodType.UNKNOWN, null, null, ValidationStatus.UNKNOWN, null))
                    : List.copyOf(windows);
            // 정규화 스냅샷처럼 제목·분류·조건·신청기간이 같으면 같은 값 (판정 입력은 조건 묶음 키로 비교)
            Map<String, Object> snapshot = Map.of("fixture", String.valueOf(List.of(title, String.valueOf(category),
                    String.valueOf(subCategory), ConditionSetKeys.hash(conditions(null), residenceCodes), periods)));
            PersonalizationStatus personalization = ageValidation == ValidationStatus.VALID
                    ? PersonalizationStatus.PERSONALIZABLE : PersonalizationStatus.UNKNOWN;
            return saveVersion(em, new Spec(sourceId, externalId, OpportunityType.YOUTH_POLICY, title, category,
                    subCategory, null, null, snapshot, personalization, this::conditions, residenceCodes,
                    RegionRole.RESIDENCE, residenceCodes, periods,
                    version -> YouthPolicyDetail.builder().version(version).policyNo(externalId).build()));
        }

        private List<OpportunityCondition> conditions(OpportunityVersion version) {
            List<OpportunityCondition> list = new ArrayList<>();
            list.add(condition(version, ConditionType.AGE, RequirementType.REQUIRED, "age", ageExpression, ageValidation,
                    null));
            list.add(condition(version, ConditionType.REGION, RequirementType.REQUIRED, "residenceRegion",
                    Map.of("status", regionStatus, "role", RegionRole.RESIDENCE.name()), regionValidation, null));
            codes.forEach((type, c) -> list.add(condition(version, type, RequirementType.REQUIRED, CODE_FIELDS.get(type),
                    Map.of("status", c.status(), "match", "ANY_OF", "values", c.values()), c.validation(), null)));
            list.add(condition(version, ConditionType.INCOME, RequirementType.REQUIRED, "income", incomeExpression,
                    incomeValidation, null));
            if (additionalQualification != null) {
                list.add(condition(version, ConditionType.OTHER, RequirementType.REQUIRED, "additionalQualification",
                        Map.of("status", "UNSTRUCTURED"), ValidationStatus.NEEDS_REVIEW, additionalQualification));
            }
            if (participationRestriction != null) {
                list.add(condition(version, ConditionType.OTHER, RequirementType.EXCLUSION, "participationRestriction",
                        Map.of("status", "UNSTRUCTURED"), ValidationStatus.NEEDS_REVIEW, participationRestriction));
            }
            return list;
        }

        /** 소득 expression. 정규화는 unitConfirmed를 항상 넣는다 */
        public static Map<String, Object> income(String status, Object... keyValues) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status);
            for (int i = 0; i < keyValues.length; i += 2) {
                m.put((String) keyValues[i], keyValues[i + 1]);
            }
            m.putIfAbsent("unitConfirmed", false);
            return m;
        }
    }

    // ── 직업훈련 ──

    /** 고용24 훈련과정 한 회차의 정규화 결과 */
    public static final class Training {

        private static final Set<String> CARD_REQUIRED_TARGET_CODES = Set.of("C0061", "C0061S", "C0061I");
        private static final String KDT_TARGET_CODE = "C0104";

        private final String courseId;
        private final int round;
        private final String title;
        private long sourceId = TRAINING_SOURCE_ID;
        private String subCategory = TRAINING_SUB_CATEGORY;
        private String detailCategory;
        private String ncsCode;
        private String trainingTargetCode;
        private String trainingTargetName;
        private LocalDate startDate;
        private LocalDate endDate;
        private List<String> implementationCodes = List.of();

        private Training(String courseId, int round, String title) {
            this.courseId = courseId;
            this.round = round;
            this.title = title;
        }

        public Training source(long sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        /** NCS 분류 (정규화가 NCS 코드로 찾은 대분류·중분류 이름) */
        public Training ncs(String ncsCode, String subCategory, String detailCategory) {
            this.ncsCode = ncsCode;
            this.subCategory = subCategory;
            this.detailCategory = detailCategory;
            return this;
        }

        public Training target(String code, String name) {
            this.trainingTargetCode = code;
            this.trainingTargetName = name;
            return this;
        }

        public Training dates(LocalDate startDate, LocalDate endDate) {
            this.startDate = startDate;
            this.endDate = endDate;
            return this;
        }

        /** 훈련 장소 (시행 지역, 지역 사전으로 매핑한 10자리 코드). 자격 조건이 아니다 */
        public Training implementation(String... regionCodes) {
            this.implementationCodes = List.of(regionCodes);
            return this;
        }

        public Saved save(EntityManager em) {
            Map<String, Object> snapshot = Map.of("fixture", String.valueOf(List.of(title, String.valueOf(ncsCode),
                    String.valueOf(trainingTargetCode), String.valueOf(startDate), String.valueOf(endDate),
                    implementationCodes)));
            return saveVersion(em, new Spec(sourceId, courseId + ":" + round, OpportunityType.VOCATIONAL_TRAINING, title,
                    TRAINING_CATEGORY, subCategory, detailCategory, null, snapshot, PersonalizationStatus.UNKNOWN,
                    this::conditions, List.of(), RegionRole.IMPLEMENTATION, implementationCodes,
                    List.of(new Window(PeriodType.UNKNOWN, null, null, ValidationStatus.UNKNOWN, null)),
                    version -> TrainingCourseDetail.builder().version(version).courseId(courseId).courseRound(round)
                            .trainingStartDate(startDate).trainingEndDate(endDate).ncsCode(ncsCode)
                            .trainingTargetCode(trainingTargetCode).trainingTargetName(trainingTargetName).build()));
        }

        /** 훈련유형별 자격 조건 (정규화와 같음) */
        private List<OpportunityCondition> conditions(OpportunityVersion version) {
            if (trainingTargetCode != null && CARD_REQUIRED_TARGET_CODES.contains(trainingTargetCode)) {
                return List.of(condition(version, ConditionType.TRAINING_CARD, RequirementType.REQUIRED, "trainingCard",
                        Map.of("status", "CARD_REQUIRED"), ValidationStatus.VALID, null));
            }
            OpportunityCondition unstructured = condition(version, ConditionType.TRAINING_CARD, RequirementType.REQUIRED,
                    "trainingTarget", Map.of("status", "UNSTRUCTURED"), ValidationStatus.NEEDS_REVIEW, null);
            if (KDT_TARGET_CODE.equals(trainingTargetCode)) {
                return List.of(
                        condition(version, ConditionType.PARTICIPATION_HISTORY, RequirementType.REQUIRED,
                                "kdtParticipation", Map.of("status", "ONCE_ONLY", "program", "KDT"),
                                ValidationStatus.VALID, null),
                        condition(version, ConditionType.REGION, RequirementType.REQUIRED, "residenceRegion",
                                Map.of("status", "NATIONAL", "role", RegionRole.RESIDENCE.name()),
                                ValidationStatus.VALID, null),
                        unstructured);
            }
            return List.of(unstructured);
        }
    }

    // ── 저장 ──

    private record Window(PeriodType type, LocalDate start, LocalDate end, ValidationStatus validation,
                          String periodText) {
    }

    private record Spec(long sourceId, String externalId, OpportunityType type, String title, String category,
                        String subCategory, String detailCategory, String detailUrl, Map<String, Object> snapshot,
                        PersonalizationStatus personalization,
                        Function<OpportunityVersion, List<OpportunityCondition>> conditions,
                        List<String> residenceCodes, RegionRole regionRole, List<String> regionCodes,
                        List<Window> windows, Function<OpportunityVersion, Object> detail) {
    }

    private static Saved saveVersion(EntityManager em, Spec spec) {
        LocalDateTime now = LocalDateTime.now();
        List<OpportunitySource> found = em.createQuery("select s from OpportunitySource s join fetch s.opportunity "
                        + "where s.sourceId = :sourceId and s.externalId = :externalId", OpportunitySource.class)
                .setParameter("sourceId", spec.sourceId())
                .setParameter("externalId", spec.externalId())
                .getResultList();
        Opportunity opportunity;
        if (found.isEmpty()) {
            opportunity = Opportunity.builder().opportunityType(spec.type()).title(spec.title())
                    .category(spec.category()).subCategory(spec.subCategory()).detailCategory(spec.detailCategory())
                    .detailUrl(spec.detailUrl()).build();
            em.persist(opportunity);
            em.persist(OpportunitySource.builder().opportunity(opportunity).sourceId(spec.sourceId())
                    .externalId(spec.externalId()).firstSeenAt(now).lastSeenAt(now).build());
        } else {
            opportunity = found.get(0).getOpportunity();
            found.get(0).markSeen(now, found.get(0).getSourceUrl());
        }

        OpportunityVersion current = opportunity.getCurrentVersion();
        if (current != null && spec.snapshot().equals(current.getNormalizedSnapshot())) {
            return new Saved(opportunity.getId(), current.getId(), current.getVersionNo(), false, false);
        }

        Long conditionSetId = conditionSetId(em, ConditionSetKeys.hash(spec.conditions().apply(null),
                spec.residenceCodes()));
        OpportunityVersion version = OpportunityVersion.builder().opportunity(opportunity)
                .versionNo(current == null ? 1 : current.getVersionNo() + 1)
                .normalizedSnapshot(spec.snapshot())
                .personalizationStatus(spec.personalization())
                .normalizationVersion("test-fixture")
                .conditionSetId(conditionSetId)
                .build();
        em.persist(version);
        em.persist(spec.detail().apply(version));
        spec.conditions().apply(version).forEach(em::persist);
        for (Window w : spec.windows()) {
            em.persist(ApplicationWindow.builder().version(version).periodType(w.type()).startDate(w.start())
                    .endDate(w.end()).periodText(w.periodText()).validationStatus(w.validation()).build());
        }
        for (String code : spec.regionCodes()) {
            em.persist(OpportunityRegion.builder().version(version).region(em.getReference(Region.class, code))
                    .regionRole(spec.regionRole()).build());
        }
        opportunity.changeCurrentVersion(version);
        opportunity.updateSummary(spec.title(), spec.category(), spec.subCategory(), spec.detailCategory(),
                opportunity.getDetailUrl());
        em.flush();
        return new Saved(opportunity.getId(), version.getId(), version.getVersionNo(), current == null, true);
    }

    /** 키에 해당하는 조건 묶음 ID. 없으면 만든다 (조건 묶음 엔티티는 생성자를 열지 않으므로 네이티브 SQL) */
    private static Long conditionSetId(EntityManager em, String hash) {
        List<?> ids = em.createNativeQuery("SELECT id FROM condition_sets WHERE condition_hash = :hash")
                .setParameter("hash", hash).getResultList();
        if (ids.isEmpty()) {
            em.createNativeQuery("INSERT INTO condition_sets (condition_hash, created_at) VALUES (:hash, CURRENT_TIMESTAMP)")
                    .setParameter("hash", hash).executeUpdate();
            ids = em.createNativeQuery("SELECT id FROM condition_sets WHERE condition_hash = :hash")
                    .setParameter("hash", hash).getResultList();
        }
        return ((Number) ids.get(0)).longValue();
    }

    private static OpportunityCondition condition(OpportunityVersion version, ConditionType type,
                                                  RequirementType requirement, String field,
                                                  Map<String, Object> expression, ValidationStatus validation,
                                                  String evidenceText) {
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("sourceType", "TEST_FIXTURE");
        if (evidenceText != null) {
            evidence.put("evidenceText", evidenceText);
        }
        return OpportunityCondition.builder().version(version).conditionType(type).requirementType(requirement)
                .conditionField(field).expression(expression).evidence(evidence).validationStatus(validation).build();
    }
}
