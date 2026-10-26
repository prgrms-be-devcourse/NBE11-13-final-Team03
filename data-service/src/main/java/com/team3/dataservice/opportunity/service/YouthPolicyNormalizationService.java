package com.team3.dataservice.opportunity.service;

import com.team3.dataservice.opportunity.conditionset.ConditionSetKey;
import com.team3.dataservice.opportunity.domain.entity.*;
import com.team3.dataservice.opportunity.domain.repository.*;
import com.team3.dataservice.opportunity.normalization.dto.NormalizationCommand;
import com.team3.dataservice.opportunity.normalization.dto.NormalizationResult;
import com.team3.dataservice.opportunity.normalization.dto.YouthPolicyRaw;
import com.team3.dataservice.opportunity.normalization.AgeNormalization;
import com.team3.dataservice.opportunity.normalization.AgeNormalizer;
import com.team3.dataservice.opportunity.normalization.BodyConditionDetector;
import com.team3.dataservice.opportunity.domain.status.ApplicationPeriod;
import com.team3.dataservice.opportunity.normalization.ApplicationPeriodNormalizer;
import com.team3.dataservice.opportunity.normalization.CodeConditionNormalizer;
import com.team3.dataservice.opportunity.normalization.CodeNormalization;
import com.team3.dataservice.opportunity.normalization.IncomeNormalization;
import com.team3.dataservice.opportunity.normalization.IncomeNormalizer;
import com.team3.dataservice.opportunity.normalization.RegionNormalization;
import com.team3.dataservice.opportunity.normalization.RegionNormalizer;
import com.team3.dataservice.opportunity.normalization.RegionStatus;
import com.team3.dataservice.opportunity.normalization.official.*;
import com.team3.dataservice.region.directory.RegionDirectory;
import com.team3.dataservice.region.domain.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.*;

/**
 * 온통청년 원본 한 건을 정규화해 저장한다.
 *
 * 1. 보완: API 구조화 필드 → API 자유텍스트 → 공식 원문(신뢰도 낮은 것부터, 공식 공고가 마지막)
 *    지역은 zipCd(정책거주지역코드)를 지역 사전으로 매핑해 거주지(RESIDENCE) 조건으로 저장한다.
 *    코드 필드(혼인·취업·학력·전공·특화)는 코드표로, 소득은 코드·금액·텍스트로 정규화한다.
 *    추가 자격(addAplyQlfcCndCn)·참여 제한(ptcpPrpTrgtCn) 원문은 구조화하지 않고 검토 대상 조건으로 보존한다.
 *    본문(설명·지원내용·제출서류 등)에만 적힌 주거·재산·병역 자격 표현도 원문 확인 조건으로 저장한다 (BodyConditionDetector).
 * 2. 출처 매핑: (sourceId, plcyNo)로 기존 Opportunity를 찾고, 없으면 새로 만든다
 * 3. 버전: 정규화 스냅샷이 현재 버전과 같으면 새 버전을 만들지 않는다. 다르면 새 버전과 조건·신청기간을 저장한다.
 * 4. 상세: 지원 내용·기관·신청 방법·URL·사업기간 등 화면용 정보를 버전별 YouthPolicyDetail에 저장한다.
 *    스냅샷에도 "policy"·"businessPeriod"로 넣어 상세 정보만 바뀌어도 새 버전을 만든다 (공백만 다른 원문은 같게 본다).
 *
 * 스냅샷에는 비교 기준이 되는 정규화 값만 넣는다. 기준일에 따라 바뀌는 값(현재 신청 상태), 수집·확인 시각,
 * 원문 해시, 원문 표기(신청기간 원문 등)는 넣지 않는다. 근거·원문은 조건과 신청기간 행에 보관한다.
 */
@Service
@RequiredArgsConstructor
public class YouthPolicyNormalizationService {

    public static final String NORMALIZATION_VERSION = "0.4.0";

    /** 코드 필드 → (조건 분류, 조건 필드명) */
    private static final List<CodeField> CODE_FIELDS = List.of(
            new CodeField("mrgSttsCd", ConditionType.MARITAL, "maritalStatus"),
            new CodeField("jobCd", ConditionType.EMPLOYMENT, "employmentStatus"),
            new CodeField("schoolCd", ConditionType.EDUCATION, "education"),
            new CodeField("plcyMajorCd", ConditionType.SPECIALIZATION, "major"),
            new CodeField("sBizCd", ConditionType.SPECIAL_TARGET, "specialTarget"));

    private record CodeField(String field, ConditionType type, String conditionField) {
    }

    private final AgeNormalizer ageNormalizer;
    private final ApplicationPeriodNormalizer periodNormalizer;
    private final RegionNormalizer regionNormalizer;
    private final CodeConditionNormalizer codeNormalizer;
    private final IncomeNormalizer incomeNormalizer;
    private final RegionDirectory regionDirectory;
    private final OfficialSupplementer supplementer;
    private final OpportunityRepository opportunityRepository;
    private final OpportunitySourceRepository sourceRepository;
    private final OpportunityVersionRepository versionRepository;
    private final OpportunityConditionRepository conditionRepository;
    private final ApplicationWindowRepository windowRepository;
    private final OpportunityRegionRepository opportunityRegionRepository;
    private final RegionRepository regionRepository;
    private final YouthPolicyDetailRepository detailRepository;
    private final ConditionSetRepository conditionSetRepository;

    @Transactional
    public NormalizationResult normalize(NormalizationCommand command) {
        YouthPolicyRaw raw = command.raw();
        if (raw.plcyNo() == null || raw.plcyNo().isBlank() || raw.plcyNm() == null || raw.plcyNm().isBlank()) {
            throw new IllegalArgumentException("정책번호와 정책명이 없는 원본은 정규화할 수 없습니다.");
        }
        List<OfficialDocument> documents = orderedByConfidence(command.documents());

        SupplementedAge age = supplementer.supplementAgeFromApiText(raw, ageNormalizer.normalize(raw));
        SupplementedPeriods periods = new SupplementedPeriods(periodNormalizer.normalize(raw), null,
                SupplementOutcome.NOT_APPLIED, "API 값");
        for (OfficialDocument doc : documents) {
            SupplementedAge nextAge = supplementer.supplementAge(raw, age.result(), doc);
            if (applied(nextAge.outcome())) {
                age = nextAge;
            }
            SupplementedPeriods nextPeriods = supplementer.supplementPeriods(raw, periods.periods(), doc);
            if (applied(nextPeriods.outcome())) {
                periods = nextPeriods;
            }
        }

        RegionNormalization region = regionNormalizer.normalize(raw.zipCd(), regionDirectory.index());
        Map<String, CodeNormalization> codes = new LinkedHashMap<>();
        for (CodeField f : CODE_FIELDS) {
            codes.put(f.field(), codeNormalizer.normalize(f.field(), codeValue(raw, f.field())));
        }
        IncomeNormalization income = incomeNormalizer.normalize(raw);

        Map<String, Object> snapshot = snapshot(raw, age.result(), periods.periods(), region);
        putConditions(snapshot, codes, income, raw);
        putDetail(snapshot, raw);

        Optional<OpportunitySource> existing = sourceRepository.findBySourceIdAndExternalId(command.sourceId(), raw.plcyNo());
        Opportunity opportunity;
        if (existing.isPresent()) {
            opportunity = existing.get().getOpportunity();
            existing.get().markSeen(command.seenAt(), command.sourceUrl());
        } else {
            opportunity = opportunityRepository.save(Opportunity.builder()
                    .opportunityType(OpportunityType.YOUTH_POLICY)
                    .title(raw.plcyNm().trim())
                    .category(blankToNull(raw.lclsfNm()))
                    .subCategory(blankToNull(raw.mclsfNm()))
                    .build());
            sourceRepository.save(OpportunitySource.builder()
                    .opportunity(opportunity)
                    .sourceId(command.sourceId())
                    .externalId(raw.plcyNo())
                    .sourceUrl(command.sourceUrl())
                    .firstSeenAt(command.seenAt())
                    .lastSeenAt(command.seenAt())
                    .build());
        }

        OpportunityVersion current = opportunity.getCurrentVersion();
        if (current != null && snapshot.equals(current.getNormalizedSnapshot())) {
            return new NormalizationResult(opportunity.getId(), current.getId(), current.getVersionNo(),
                    false, false, age.outcome(), periods.outcome());
        }

        // 조건 묶음 키는 저장할 조건·거주지역과 같은 값으로 만든다.
        // 조건 엔티티는 불변이라 버전을 나중에 넣을 수 없고, 버전에는 묶음 ID가 먼저 있어야 한다.
        // 그래서 버전 없는 조건으로 키를 계산하고, 버전 저장 뒤 같은 값으로 다시 만들어 저장한다 (중복 아님, 지우지 말 것)
        Long conditionSetId = conditionSetRepository.resolveId(ConditionSetKey.hash(
                conditions(null, age, region, codes, income, raw), residenceCodes(region)));
        OpportunityVersion version = versionRepository.save(OpportunityVersion.builder()
                .opportunity(opportunity)
                .versionNo(current == null ? 1 : current.getVersionNo() + 1)
                .normalizedSnapshot(snapshot)
                .personalizationStatus(personalizationStatus(age.result()))
                .normalizationVersion(NORMALIZATION_VERSION)
                .conditionSetId(conditionSetId)
                .build());
        detailRepository.save(detail(version, raw));
        conditionRepository.saveAll(conditions(version, age, region, codes, income, raw));
        windowRepository.saveAll(windows(version, periods.periods()));
        opportunityRegionRepository.saveAll(regions(version, region));

        opportunity.changeCurrentVersion(version);
        opportunity.updateSummary(raw.plcyNm().trim(), blankToNull(raw.lclsfNm()), blankToNull(raw.mclsfNm()),
                opportunity.getDetailUrl());

        return new NormalizationResult(opportunity.getId(), version.getId(), version.getVersionNo(),
                existing.isEmpty(), true, age.outcome(), periods.outcome());
    }

    // ── 스냅샷 (String, Integer, Boolean, List, Map만 사용: DB(jsonb)에서 읽어도 equals가 성립하도록) ──

    static Map<String, Object> snapshot(YouthPolicyRaw raw, AgeNormalization age, List<ApplicationPeriod> periods,
                                       RegionNormalization region) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", raw.plcyNm().trim());
        Map<String, Object> category = new LinkedHashMap<>();
        putIfNotNull(category, "large", blankToNull(raw.lclsfNm()));
        putIfNotNull(category, "medium", blankToNull(raw.mclsfNm()));
        snapshot.put("category", category);

        Map<String, Object> ageMap = new LinkedHashMap<>(ageExpression(age));
        ageMap.put("validationStatus", age.validationStatus().name());
        ageMap.put("dataConflict", age.dataConflict());
        snapshot.put("age", ageMap);

        List<Map<String, Object>> windows = new ArrayList<>();
        for (ApplicationPeriod p : periods) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("periodType", p.periodType().name());
            putIfNotNull(w, "startDate", dateText(p.startDate()));
            putIfNotNull(w, "endDate", dateText(p.endDate()));
            w.put("validationStatus", p.validationStatus().name());
            windows.add(w);
        }
        snapshot.put("applicationPeriods", windows);

        Map<String, Object> regionMap = new LinkedHashMap<>();
        regionMap.put("status", region.status().name());
        regionMap.put("codes", region.regionCodes());
        regionMap.put("unmappedCodes", region.unmappedCodes());
        regionMap.put("validationStatus", region.validationStatus().name());
        snapshot.put("region", regionMap);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("trackingEligible", true);
        usage.put("exclusionReasons", exclusionReasons(age, periods, region));
        snapshot.put("usage", usage);
        return snapshot;
    }

    /** 상세 정보를 스냅샷에 더한다. 비교용이라 원문은 공백을 정리하고, 날짜는 읽은 값만 넣는다 */
    static void putDetail(Map<String, Object> snapshot, YouthPolicyRaw raw) {
        Map<String, Object> policy = new LinkedHashMap<>();
        putIfNotNull(policy, "description", normalizedText(raw.plcyExplnCn()));
        putIfNotNull(policy, "supportContent", normalizedText(raw.plcySprtCn()));
        putIfNotNull(policy, "keywords", normalizedText(raw.plcyKywdNm()));
        putIfNotNull(policy, "supervisingInstitutionName", normalizedText(raw.sprvsnInstCdNm()));
        putIfNotNull(policy, "operatingInstitutionName", normalizedText(raw.operInstCdNm()));
        putIfNotNull(policy, "applicationMethod", normalizedText(raw.plcyAplyMthdCn()));
        putIfNotNull(policy, "screeningMethod", normalizedText(raw.srngMthdCn()));
        putIfNotNull(policy, "submissionDocuments", normalizedText(raw.sbmsnDcmntCn()));
        putIfNotNull(policy, "applicationUrl", blankToNull(raw.aplyUrlAddr()));
        putIfNotNull(policy, "referenceUrl1", blankToNull(raw.refUrlAddr1()));
        putIfNotNull(policy, "referenceUrl2", blankToNull(raw.refUrlAddr2()));
        putIfNotNull(policy, "supportScale", blankToNull(raw.sprtSclCnt()));
        putIfNotNull(policy, "otherNotes", normalizedText(raw.etcMttrCn()));
        putIfNotNull(policy, "firstRegisteredDate", dateText(parseDate(raw.frstRegDt())));
        snapshot.put("policy", policy);

        Map<String, Object> business = new LinkedHashMap<>();
        putIfNotNull(business, "startDate", dateText(parseDate(raw.bizPrdBgngYmd())));
        putIfNotNull(business, "endDate", dateText(parseDate(raw.bizPrdEndYmd())));
        putIfNotNull(business, "text", normalizedText(raw.bizPrdEtcCn()));
        snapshot.put("businessPeriod", business);
    }

    /** 화면용 상세. 원문은 줄바꿈을 살려 앞뒤 공백만 제거한다 */
    static YouthPolicyDetail detail(OpportunityVersion version, YouthPolicyRaw raw) {
        return YouthPolicyDetail.builder()
                .version(version)
                .policyNo(raw.plcyNo().trim())
                .description(blankToNull(raw.plcyExplnCn()))
                .supportContent(blankToNull(raw.plcySprtCn()))
                .keywords(blankToNull(raw.plcyKywdNm()))
                .supervisingInstitutionName(blankToNull(raw.sprvsnInstCdNm()))
                .operatingInstitutionName(blankToNull(raw.operInstCdNm()))
                .applicationMethod(blankToNull(raw.plcyAplyMthdCn()))
                .screeningMethod(blankToNull(raw.srngMthdCn()))
                .submissionDocuments(blankToNull(raw.sbmsnDcmntCn()))
                .applicationUrl(blankToNull(raw.aplyUrlAddr()))
                .referenceUrl1(blankToNull(raw.refUrlAddr1()))
                .referenceUrl2(blankToNull(raw.refUrlAddr2()))
                .businessStartDate(parseDate(raw.bizPrdBgngYmd()))
                .businessEndDate(parseDate(raw.bizPrdEndYmd()))
                .businessPeriodText(blankToNull(raw.bizPrdEtcCn()))
                .supportScale(blankToNull(raw.sprtSclCnt()))
                .otherNotes(blankToNull(raw.etcMttrCn()))
                .firstRegisteredDate(parseDate(raw.frstRegDt()))
                .build();
    }

    /** 숫자만 모아 앞 8자리를 yyyyMMdd로 읽는다 ("20260101", "2026-01-01 10:00:00" 모두 가능). 읽을 수 없으면 null */
    static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 8) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(digits.substring(0, 4)), Integer.parseInt(digits.substring(4, 6)),
                    Integer.parseInt(digits.substring(6, 8)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** 코드·소득·자유텍스트 조건을 스냅샷과 활용 제한 사유에 더한다 */
    @SuppressWarnings("unchecked")
    static void putConditions(Map<String, Object> snapshot, Map<String, CodeNormalization> codes,
                              IncomeNormalization income, YouthPolicyRaw raw) {
        Map<String, Object> codeMap = new LinkedHashMap<>();
        codes.forEach((field, c) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", c.status().name());
            m.put("values", c.values());
            m.put("unmappedCodes", c.unmappedCodes());
            m.put("validationStatus", c.validationStatus().name());
            codeMap.put(field, m);
        });
        snapshot.put("codes", codeMap);
        snapshot.put("income", incomeExpression(income, true));

        Map<String, Object> text = new LinkedHashMap<>();
        putIfNotNull(text, "addAplyQlfcCndCn", normalizedText(raw.addAplyQlfcCndCn()));
        putIfNotNull(text, "ptcpPrpTrgtCn", normalizedText(raw.ptcpPrpTrgtCn()));
        snapshot.put("textConditions", text);
        // 본문 조건은 찾았을 때만 넣는다 (없는 정책의 스냅샷은 그대로라 새 버전이 생기지 않는다)
        BodyConditionDetector.Detection body = BodyConditionDetector.detect(raw);
        if (!body.isEmpty()) {
            snapshot.put("bodyConditions", body.kindNames());
        }

        List<String> reasons = (List<String>) ((Map<String, Object>) snapshot.get("usage")).get("exclusionReasons");
        codes.forEach((field, c) -> {
            if (c.validationStatus() != ValidationStatus.VALID && c.validationStatus() != ValidationStatus.UNKNOWN) {
                reasons.add(field + "_" + c.validationStatus().name());
            }
        });
        if (income.validationStatus() != ValidationStatus.VALID && income.validationStatus() != ValidationStatus.UNKNOWN) {
            reasons.add("INCOME_" + income.validationStatus().name());
        }
        if (!text.isEmpty() || !body.isEmpty()) {
            reasons.add("UNSTRUCTURED_CONDITION_TEXT");
        }
    }

    /** 기준일과 무관한 활용 제한 사유 */
    static List<String> exclusionReasons(AgeNormalization age, List<ApplicationPeriod> periods, RegionNormalization region) {
        List<String> reasons = new ArrayList<>();
        if (age.validationStatus() != ValidationStatus.VALID) {
            reasons.add("AGE_" + age.validationStatus().name());
        }
        if (age.dataConflict()) {
            reasons.add("AGE_SOURCE_CONFLICT");
        }
        if (periods.stream().noneMatch(p -> p.validationStatus() == ValidationStatus.VALID)) {
            reasons.add("APPLICATION_PERIOD_UNCONFIRMED");
        }
        if (periods.stream().anyMatch(p -> p.validationStatus() == ValidationStatus.INVALID)) {
            reasons.add("APPLICATION_PERIOD_INVALID");
        }
        if (region.validationStatus() != ValidationStatus.VALID) {
            reasons.add("REGION_" + region.validationStatus().name());
        }
        return reasons;
    }

    private static Map<String, Object> ageExpression(AgeNormalization age) {
        Map<String, Object> expression = new LinkedHashMap<>();
        expression.put("status", age.status().name());
        putIfNotNull(expression, "min", age.min());
        putIfNotNull(expression, "max", age.max());
        putIfNotNull(expression, "bornOnOrAfter", dateText(age.bornOnOrAfter()));
        putIfNotNull(expression, "bornOnOrBefore", dateText(age.bornOnOrBefore()));
        return expression;
    }

    // ── 엔티티 변환 ──

    /** 버전의 자격 조건 전체 (저장 순서대로). version이 null이면 조건 묶음 키 계산용 임시 조건 */
    private static List<OpportunityCondition> conditions(OpportunityVersion version, SupplementedAge age,
                                                         RegionNormalization region,
                                                         Map<String, CodeNormalization> codes,
                                                         IncomeNormalization income, YouthPolicyRaw raw) {
        List<OpportunityCondition> list = new ArrayList<>();
        list.add(ageCondition(version, age));
        list.add(regionCondition(version, region));
        for (CodeField f : CODE_FIELDS) {
            list.add(codeCondition(version, f, codes.get(f.field())));
        }
        list.add(incomeCondition(version, income));
        textCondition(version, ConditionType.OTHER, RequirementType.REQUIRED, "additionalQualification",
                "addAplyQlfcCndCn", raw.addAplyQlfcCndCn()).ifPresent(list::add);
        textCondition(version, ConditionType.OTHER, RequirementType.EXCLUSION, "participationRestriction",
                "ptcpPrpTrgtCn", raw.ptcpPrpTrgtCn()).ifPresent(list::add);
        bodyCondition(version, BodyConditionDetector.detect(raw)).ifPresent(list::add);
        return list;
    }

    /** 거주지역(RESIDENCE) 행으로 저장하는 코드. regions()와 같은 기준 */
    private static List<String> residenceCodes(RegionNormalization region) {
        return region.status() == RegionStatus.SPECIFIC ? region.regionCodes() : List.of();
    }

    private static OpportunityCondition ageCondition(OpportunityVersion version, SupplementedAge age) {
        AgeNormalization result = age.result();
        Map<String, Object> evidence = new HashMap<>();
        Evidence e = age.evidence();
        if (e != null) {
            evidence.put("sourceType", e.sourceType().name());
            evidence.put("sourceUrl", e.sourceUrl());
            evidence.put("sourceHash", e.sourceHash());
            evidence.put("evidenceText", e.evidenceText());
            evidence.put("confidence", e.confidence().name());
            evidence.put("verifiedAt", e.verifiedAt() == null ? null : e.verifiedAt().toString());
        }
        evidence.put("dataConflict", result.dataConflict());
        evidence.put("reason", result.reason());
        evidence.put("outcome", age.outcome().name());
        evidence.put("sourceFields", List.of("sprtTrgtMinAge", "sprtTrgtMaxAge", "sprtTrgtAgeLmtYn"));
        evidence.put("raw", result.raw());

        return OpportunityCondition.builder()
                .version(version)
                .conditionType(ConditionType.AGE)
                .requirementType(RequirementType.REQUIRED)
                .conditionField("age")
                .expression(ageExpression(result))
                .evidence(evidence)
                .validationStatus(result.validationStatus())
                .build();
    }

    private static OpportunityCondition regionCondition(OpportunityVersion version, RegionNormalization region) {
        Map<String, Object> expression = new LinkedHashMap<>();
        expression.put("status", region.status().name());
        expression.put("role", RegionRole.RESIDENCE.name());

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("sourceType", SourceType.YOUTH_CENTER_API.name());
        evidence.put("confidence", SourceType.YOUTH_CENTER_API.confidence().name());
        evidence.put("sourceFields", List.of("zipCd"));
        evidence.put("raw", Collections.singletonMap("zipCd", region.rawZipCd()));
        evidence.put("unmappedCodes", region.unmappedCodes());
        evidence.put("reason", region.reason());

        return OpportunityCondition.builder()
                .version(version)
                .conditionType(ConditionType.REGION)
                .requirementType(RequirementType.REQUIRED)
                .conditionField("residenceRegion")
                .expression(expression)
                .evidence(evidence)
                .validationStatus(region.validationStatus())
                .build();
    }

    private static OpportunityCondition codeCondition(OpportunityVersion version, CodeField f, CodeNormalization c) {
        Map<String, Object> expression = new LinkedHashMap<>();
        expression.put("status", c.status().name());
        expression.put("match", "ANY_OF");
        expression.put("values", c.values());

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("sourceType", SourceType.YOUTH_CENTER_API.name());
        evidence.put("confidence", SourceType.YOUTH_CENTER_API.confidence().name());
        evidence.put("sourceFields", List.of(f.field()));
        evidence.put("raw", Collections.singletonMap(f.field(), c.raw()));
        evidence.put("unmappedCodes", c.unmappedCodes());
        evidence.put("reason", c.reason());

        return OpportunityCondition.builder()
                .version(version)
                .conditionType(f.type())
                .requirementType(RequirementType.REQUIRED)
                .conditionField(f.conditionField())
                .expression(expression)
                .evidence(evidence)
                .validationStatus(c.validationStatus())
                .build();
    }

    private static OpportunityCondition incomeCondition(OpportunityVersion version, IncomeNormalization income) {
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("sourceType", SourceType.YOUTH_CENTER_API.name());
        evidence.put("confidence", SourceType.YOUTH_CENTER_API.confidence().name());
        evidence.put("sourceFields", List.of("earnCndSeCd", "earnMinAmt", "earnMaxAmt", "earnEtcCn", "addAplyQlfcCndCn"));
        evidence.put("evidenceText", income.evidenceText());
        evidence.put("dataConflict", income.dataConflict());
        evidence.put("reason", income.reason());
        evidence.put("raw", income.raw());

        return OpportunityCondition.builder()
                .version(version)
                .conditionType(ConditionType.INCOME)
                .requirementType(RequirementType.REQUIRED)
                .conditionField("income")
                .expression(incomeExpression(income, false))
                .evidence(evidence)
                .validationStatus(income.validationStatus())
                .build();
    }

    /** 원문이 있을 때만 비구조화 조건으로 보존한다. 빈 값을 "제한 없음"으로 해석하지 않으며 행도 만들지 않는다. */
    private static Optional<OpportunityCondition> textCondition(OpportunityVersion version, ConditionType type,
                                                                RequirementType requirement, String conditionField,
                                                                String sourceField, String value) {
        String text = normalizedText(value);
        if (text == null) {
            return Optional.empty();
        }
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("sourceType", SourceType.API_TEXT.name());
        evidence.put("confidence", SourceType.API_TEXT.confidence().name());
        evidence.put("sourceFields", List.of(sourceField));
        evidence.put("evidenceText", value.trim());
        evidence.put("reason", "자유텍스트 조건 — 구조화 전 (AI 구조화·검토 대상)");

        return Optional.of(OpportunityCondition.builder()
                .version(version)
                .conditionType(type)
                .requirementType(requirement)
                .conditionField(conditionField)
                .expression(Map.of("status", "UNSTRUCTURED"))
                .evidence(evidence)
                .validationStatus(ValidationStatus.NEEDS_REVIEW)
                .build());
    }

    /**
     * 본문에만 적힌 판정하지 않는 자격 조건(주거·재산·병역). 찾았을 때만 원문 확인 조건 한 건을 만든다.
     * expression의 kinds는 찾은 종류라 조건 묶음 키에 들어간다 (종류가 바뀌면 다른 묶음)
     */
    private static Optional<OpportunityCondition> bodyCondition(OpportunityVersion version,
                                                                BodyConditionDetector.Detection body) {
        if (body.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("sourceType", SourceType.API_TEXT.name());
        evidence.put("confidence", SourceType.API_TEXT.confidence().name());
        evidence.put("sourceFields", body.sourceFields());
        evidence.put("evidenceText", body.evidenceText());
        evidence.put("reason", "본문에 판정하지 않는 자격 조건(주거·재산·병역) 표현이 있음 — 원문 확인 필요");

        Map<String, Object> expression = new LinkedHashMap<>();
        expression.put("status", "UNSTRUCTURED");
        expression.put("kinds", body.kindNames());
        return Optional.of(OpportunityCondition.builder()
                .version(version)
                .conditionType(ConditionType.OTHER)
                .requirementType(RequirementType.REQUIRED)
                .conditionField("bodyQualification")
                .expression(expression)
                .evidence(evidence)
                .validationStatus(ValidationStatus.NEEDS_REVIEW)
                .build());
    }

    /** snapshot=true면 비교용(근거 문장 제외) */
    private static Map<String, Object> incomeExpression(IncomeNormalization income, boolean snapshot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", income.status().name());
        putIfNotNull(m, "type", income.type() == null ? null : income.type().name());
        putIfNotNull(m, "min", jsonNumber(income.min()));
        putIfNotNull(m, "max", jsonNumber(income.max()));
        putIfNotNull(m, "minInclusive", income.minInclusive());
        putIfNotNull(m, "maxInclusive", income.maxInclusive());
        putIfNotNull(m, "period", income.period());
        putIfNotNull(m, "subject", income.subject());
        m.put("unitConfirmed", income.unitConfirmed());
        if (snapshot) {
            m.put("validationStatus", income.validationStatus().name());
            m.put("dataConflict", income.dataConflict());
        }
        return m;
    }

    /** JSON 왕복 후에도 equals가 성립하도록: 정수는 Integer/Long, 소수는 문자열 */
    static Object jsonNumber(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal v = value.stripTrailingZeros();
        if (v.scale() <= 0) {
            long l = v.longValueExact();
            return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? (Object) (int) l : (Object) l;
        }
        return v.toPlainString();
    }

    /** 공백만 다른 원문은 같은 조건으로 본다 */
    static String normalizedText(String value) {
        return value == null || value.isBlank() ? null : value.trim().replaceAll("\\s+", " ");
    }

    private static String codeValue(YouthPolicyRaw raw, String field) {
        return switch (field) {
            case "mrgSttsCd" -> raw.mrgSttsCd();
            case "jobCd" -> raw.jobCd();
            case "schoolCd" -> raw.schoolCd();
            case "plcyMajorCd" -> raw.plcyMajorCd();
            case "sBizCd" -> raw.sBizCd();
            default -> throw new IllegalArgumentException(field);
        };
    }

    /** SPECIFIC일 때만 지역 행을 만든다 (NATIONAL·UNKNOWN은 행 없음) */
    private List<OpportunityRegion> regions(OpportunityVersion version, RegionNormalization region) {
        if (region.status() != RegionStatus.SPECIFIC) {
            return List.of();
        }
        return region.regionCodes().stream()
                .map(code -> OpportunityRegion.builder()
                        .version(version)
                        .region(regionRepository.getReferenceById(code))
                        .regionRole(RegionRole.RESIDENCE)
                        .build())
                .toList();
    }

    private static List<ApplicationWindow> windows(OpportunityVersion version, List<ApplicationPeriod> periods) {
        return periods.stream()
                .map(p -> ApplicationWindow.builder()
                        .version(version)
                        .periodType(p.periodType())
                        .startDate(p.startDate())
                        .endDate(p.endDate())
                        .periodText(p.periodText())
                        .validationStatus(p.validationStatus())
                        .build())
                .toList();
    }

    /** 연령이 확인되면 자동 비교 가능. 대상별 분리(SPLIT_REQUIRED) 판단은 텍스트·AI 단계에서 정한다. */
    private static PersonalizationStatus personalizationStatus(AgeNormalization age) {
        return age.validationStatus() == ValidationStatus.VALID
                ? PersonalizationStatus.PERSONALIZABLE
                : PersonalizationStatus.UNKNOWN;
    }

    // ── 보조 ──

    private static List<OfficialDocument> orderedByConfidence(List<OfficialDocument> documents) {
        if (documents == null) {
            return List.of();
        }
        // LOW → MEDIUM → HIGH 순서: 공식 공고가 마지막에 적용되어 최종 결정을 내린다
        return documents.stream()
                .filter(d -> d != null && d.sourceType() != null)
                .sorted(Comparator.comparing((OfficialDocument d) -> d.sourceType().confidence()).reversed())
                .toList();
    }

    private static boolean applied(SupplementOutcome outcome) {
        return outcome != SupplementOutcome.NOT_APPLIED && outcome != SupplementOutcome.DOCUMENT_MISMATCH;
    }

    private static String dateText(LocalDate date) {
        return date == null ? null : date.toString();
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
