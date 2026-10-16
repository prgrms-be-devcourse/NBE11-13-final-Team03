package com.team3.backend.eligibility.benchmark;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.opportunity.dto.NormalizationCommand;
import com.team3.backend.opportunity.dto.YouthPolicyRaw;
import com.team3.backend.opportunity.service.YouthPolicyNormalizationService;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.region.domain.repository.RegionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 병목 분석 벤치마크용 데이터 준비. EligibilityBenchmarkTest와 같은 템플릿·복제 방식을 쓴다
 * (기존 테스트를 수정하지 않기 위해 같은 내용을 이 클래스로 옮겨 둠).
 */
class BenchmarkData {

    static final long CLONE_BASE = 1_000_000L;

    enum Complexity { SIMPLE, COMPLEX, UNCLEAR, MIXED }

    /** 판정 프로필 종류. 사용자 ID = ordinal + 1 */
    enum ProfileKind {
        FULL("강남·28세, 대부분 입력, 특화대상 PROVIDED"),
        MINIMAL("강남·28세, 필수 4항목만"),
        SPECIAL_NA("FULL과 같고 특화대상 NOT_APPLICABLE"),
        OLD("강남·41세"),
        YOUNG("강남·16세"),
        BUSAN("부산 해운대·28세"),
        JANGAN("수원 장안구·28세 (지역 압축 확인)");

        final String description;

        ProfileKind(String description) {
            this.description = description;
        }

        long userId() {
            return ordinal() + 1L;
        }
    }

    static final Map<Complexity, List<String>> PATTERNS = Map.of(
            Complexity.SIMPLE, List.of("S1", "S2"),
            Complexity.COMPLEX, List.of("C1", "C2"),
            Complexity.UNCLEAR, List.of("U1", "U2"),
            Complexity.MIXED, List.of("S1", "S2", "C1", "S1", "U1", "S2", "C2", "S1", "U2", "C1"));

    record Template(String key, Complexity complexity, long opportunityId, long versionId) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate writeTx;
    private final RegionRepository regionRepository;
    private final RegionDirectory regionDirectory;
    private final EligibilityProfileRepository profileRepository;
    private final YouthPolicyNormalizationService normalizationService;

    BenchmarkData(JdbcTemplate jdbc, TransactionTemplate writeTx, RegionRepository regionRepository,
                  RegionDirectory regionDirectory, EligibilityProfileRepository profileRepository,
                  YouthPolicyNormalizationService normalizationService) {
        this.jdbc = jdbc;
        this.writeTx = writeTx;
        this.regionRepository = regionRepository;
        this.regionDirectory = regionDirectory;
        this.profileRepository = profileRepository;
        this.normalizationService = normalizationService;
    }

    void seedRegions() {
        writeTx.executeWithoutResult(s -> {
            Region seoul = region("1100000000", "서울특별시", null);
            region("1168000000", "강남구", seoul);
            region("1144000000", "마포구", seoul);
            region("1111000000", "종로구", seoul);
            Region gyeonggi = region("4100000000", "경기도", null);
            Region suwon = region("4111000000", "수원시", gyeonggi);
            region("4111100000", "장안구", suwon);
            region("4111300000", "권선구", suwon);
            region("4113000000", "성남시", gyeonggi);
            Region busan = region("2600000000", "부산광역시", null);
            region("2635000000", "해운대구", busan);
            region("2623000000", "부산진구", busan);
            Region daegu = region("2700000000", "대구광역시", null);
            region("2720000000", "남구", daegu);
            region("2711000000", "중구", daegu);
        });
        regionDirectory.refresh();
    }

    private Region region(String code, String name, Region parent) {
        return regionRepository.save(Region.builder().regionCode(code).regionName(name).parent(parent).build());
    }

    void seedProfiles() {
        writeTx.executeWithoutResult(s -> {
            for (ProfileKind kind : ProfileKind.values()) {
                profileRepository.save(profile(kind));
            }
        });
    }

    private EligibilityProfile profile(ProfileKind kind) {
        LocalDate birth = switch (kind) {
            case OLD -> LocalDate.of(1985, 1, 1);
            case YOUNG -> LocalDate.of(2010, 1, 1);
            default -> LocalDate.of(1998, 5, 15);
        };
        String residence = switch (kind) {
            case BUSAN -> "2635000000";
            case JANGAN -> "4111100000";
            default -> "1168000000";
        };
        EligibilityProfile.EligibilityProfileBuilder b = EligibilityProfile.builder()
                .userId(kind.userId())
                .birthDate(birth)
                .residenceRegion(regionRepository.getReferenceById(residence))
                .employmentStatus(EmploymentStatus.UNEMPLOYED)
                .educationLevel(EducationLevel.UNIVERSITY);
        if (kind == ProfileKind.MINIMAL) {
            return b.build();
        }
        b.enrollmentStatus(EnrollmentStatus.GRADUATED)
                .maritalStatus(MaritalStatus.SINGLE)
                .housingStatus(HousingStatus.MONTHLY_RENT)
                .homelessStatus(YesNoUnknown.YES)
                .personalIncome(IncomeAnswer.provided(new BigDecimal("2400000"), IncomePeriod.MONTHLY))
                .householdIncome(IncomeAnswer.provided(new BigDecimal("48000000"), IncomePeriod.YEARLY))
                .medianIncomeStatus(AnswerStatus.PROVIDED)
                .medianIncomePercent(new BigDecimal("120"))
                .studentLoanBracketStatus(AnswerStatus.PROVIDED)
                .studentLoanBracket(6)
                .householdSize(2)
                .majorField(MajorField.ENGINEERING)
                .militaryServiceStatus(MilitaryServiceStatus.NOT_APPLICABLE);
        if (kind == ProfileKind.SPECIAL_NA) {
            b.specialTargetStatus(AnswerStatus.NOT_APPLICABLE).specialTargets(new HashSet<>());
        } else {
            b.specialTargetStatus(AnswerStatus.PROVIDED).specialTargets(new HashSet<>(Set.of(SpecialTarget.WOMAN)));
        }
        return b.build();
    }

    Map<String, Template> normalizeTemplates() {
        Map<String, YouthPolicyRaw> raws = new LinkedHashMap<>();
        raws.put("S1", base("S1", "청년 월세 지원", "19", "34", "Y", "0057001", "20261001 ~ 20261031", "11680")
                .mrgSttsCd("0055003").jobCd("0013010").schoolCd("0049010").plcyMajorCd("0011009")
                .sBizCd("0014010").earnCndSeCd("0043001").build());
        raws.put("S2", base("S2", "청년 취업 상담", "19", "39", "Y", "0057002", null, "41111")
                .mrgSttsCd("0055003").jobCd("0013010").schoolCd("0049010").plcyMajorCd("0011009")
                .sBizCd("0014010").earnCndSeCd("0043001").build());
        raws.put("C1", base("C1", "청년 주거 정착 지원", "19", "39", "Y", "0057001",
                "20260901 ~ 20260930, 20261101 ~ 20261130", "11680,11440,26350,27200")
                .mrgSttsCd("0055002").jobCd("0013003,0013006").schoolCd("0049005,0049006,0049007")
                .plcyMajorCd("0011005").sBizCd("0014002").earnCndSeCd("0043003")
                .earnEtcCn("가구 기준중위소득 150% 이하")
                .addAplyQlfcCndCn("공고일 기준 해당 지역에 6개월 이상 거주한 자")
                .ptcpPrpTrgtCn("타 기관 동일 사업 참여자 및 휴학생 제외").build());
        raws.put("C2", base("C2", "청년 학자금 이자 지원", "19", "39", "Y", "0057001",
                "20261010 ~ 20261020", "41111,41113,11110,26230")
                .mrgSttsCd("0055003").jobCd("0013001,0013003").schoolCd("0049007,0049008")
                .plcyMajorCd("0011001,0011002,0011003").sBizCd("0014001").earnCndSeCd("0043003")
                .earnEtcCn("학자금 지원구간 8구간 이하")
                .addAplyQlfcCndCn("대학 졸업 후 2년 이내인 자")
                .ptcpPrpTrgtCn("동일 목적의 정부 지원을 받는 자 제외").build());
        raws.put("U1", base("U1", "청년 활동 지원", "0", "0", "Y", "0057001", null, "99999")
                .jobCd("0013009").schoolCd("0049009").plcyMajorCd("0011010").sBizCd("0014009")
                .earnCndSeCd("0043002").earnMaxAmt("3000").build());
        raws.put("U2", base("U2", "청년 역량 강화", "19", "34", "N", null, null, null)
                .mrgSttsCd("0055003,0055002")
                .addAplyQlfcCndCn("세부 자격은 공고문 참조").build());

        Map<String, Template> templates = new LinkedHashMap<>();
        for (Map.Entry<String, YouthPolicyRaw> e : raws.entrySet()) {
            normalizationService.normalize(new NormalizationCommand(1L, e.getValue(), List.of(),
                    "https://example.invalid/" + e.getKey(), LocalDateTime.of(2026, 10, 14, 9, 0)));
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT o.id, o.current_version_id FROM opportunities o "
                            + "JOIN opportunity_sources s ON s.opportunity_id = o.id WHERE s.external_id = ?", e.getKey());
            Complexity complexity = switch (e.getKey().charAt(0)) {
                case 'S' -> Complexity.SIMPLE;
                case 'C' -> Complexity.COMPLEX;
                default -> Complexity.UNCLEAR;
            };
            templates.put(e.getKey(), new Template(e.getKey(), complexity,
                    ((Number) row.get("id")).longValue(), ((Number) row.get("current_version_id")).longValue()));
        }
        // 템플릿은 복제 원본으로만 쓰고 판정 대상에서는 뺀다
        jdbc.update("UPDATE opportunities SET current_version_id = NULL WHERE id < ?", CLONE_BASE);
        return templates;
    }

    private static YouthPolicyRaw.YouthPolicyRawBuilder base(String no, String name, String min, String max, String limit,
                                                             String periodCode, String period, String zip) {
        return YouthPolicyRaw.builder().plcyNo(no).plcyNm(name)
                .sprtTrgtMinAge(min).sprtTrgtMaxAge(max).sprtTrgtAgeLmtYn(limit)
                .aplyPrdSeCd(periodCode).aplyYmd(period).zipCd(zip).lclsfNm("일자리");
    }

    void verifyTemplateCharacteristics(Map<String, Template> templates) {
        for (Template t : templates.values()) {
            List<String> statuses = jdbc.queryForList(
                    "SELECT validation_status FROM opportunity_conditions WHERE version_id = ? "
                            + "UNION ALL SELECT validation_status FROM application_windows WHERE version_id = ?",
                    String.class, t.versionId(), t.versionId());
            long notValid = statuses.stream().filter(s -> !"VALID".equals(s)).count();
            switch (t.complexity()) {
                case SIMPLE -> assertThat(notValid).as("%s: 단순 템플릿은 모든 조건·기간이 VALID", t.key()).isZero();
                case COMPLEX -> {
                    assertThat(count("SELECT count(*) FROM opportunity_conditions WHERE version_id = " + t.versionId()
                            + " AND requirement_type = 'EXCLUSION'")).as("%s: 제외 조건", t.key()).isPositive();
                    assertThat(count("SELECT count(*) FROM opportunity_regions WHERE version_id = " + t.versionId()))
                            .as("%s: 복수 지역", t.key()).isGreaterThanOrEqualTo(2);
                }
                case UNCLEAR -> assertThat(notValid).as("%s: 불명확 템플릿", t.key()).isGreaterThanOrEqualTo(3);
                default -> {
                }
            }
        }
    }

    Map<String, List<Long>> seedClones(Map<String, Template> templates, Complexity complexity, int size) {
        List<String> pattern = PATTERNS.get(complexity);
        Map<String, List<Long>> ids = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            ids.computeIfAbsent(pattern.get(i % pattern.size()), k -> new ArrayList<>()).add(CLONE_BASE + i);
        }
        for (Map.Entry<String, List<Long>> e : ids.entrySet()) {
            Template t = templates.get(e.getKey());
            Long[] cloneIds = e.getValue().toArray(Long[]::new);
            cloneSql("INSERT INTO opportunities (id, opportunity_type, title, category, detail_url, current_version_id, created_at, updated_at) "
                    + "SELECT x.id, o.opportunity_type, o.title || ' #' || x.id, o.category, o.detail_url, NULL, now(), now() "
                    + "FROM opportunities o, unnest(?::bigint[]) AS x(id) WHERE o.id = ?", cloneIds, t.opportunityId());
            cloneSql("INSERT INTO opportunity_versions (id, opportunity_id, version_no, normalized_snapshot, personalization_status, normalization_version, created_at) "
                    + "SELECT x.id, x.id, v.version_no, v.normalized_snapshot, v.personalization_status, v.normalization_version, now() "
                    + "FROM opportunity_versions v, unnest(?::bigint[]) AS x(id) WHERE v.id = ?", cloneIds, t.versionId());
            cloneSql("INSERT INTO opportunity_conditions (version_id, condition_type, requirement_type, condition_field, expression, evidence, validation_status, created_at) "
                    + "SELECT x.id, c.condition_type, c.requirement_type, c.condition_field, c.expression, c.evidence, c.validation_status, now() "
                    + "FROM opportunity_conditions c, unnest(?::bigint[]) AS x(id) WHERE c.version_id = ? ORDER BY x.id, c.id", cloneIds, t.versionId());
            cloneSql("INSERT INTO application_windows (version_id, period_type, start_date, end_date, period_text, scope, validation_status) "
                    + "SELECT x.id, w.period_type, w.start_date, w.end_date, w.period_text, w.scope, w.validation_status "
                    + "FROM application_windows w, unnest(?::bigint[]) AS x(id) WHERE w.version_id = ? ORDER BY x.id, w.id", cloneIds, t.versionId());
            cloneSql("INSERT INTO opportunity_regions (version_id, region_code, region_role) "
                    + "SELECT x.id, r.region_code, r.region_role "
                    + "FROM opportunity_regions r, unnest(?::bigint[]) AS x(id) WHERE r.version_id = ? ORDER BY x.id, r.id", cloneIds, t.versionId());
        }
        jdbc.update("UPDATE opportunities SET current_version_id = id WHERE id >= ?", CLONE_BASE);
        return ids;
    }

    private void cloneSql(String sql, Long[] ids, long sourceId) {
        jdbc.update(sql, ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids));
            ps.setLong(2, sourceId);
        });
    }

    void clearClones() {
        jdbc.update("UPDATE opportunities SET current_version_id = NULL WHERE id >= ?", CLONE_BASE);
        jdbc.update("DELETE FROM opportunity_regions WHERE version_id >= ?", CLONE_BASE);
        jdbc.update("DELETE FROM application_windows WHERE version_id >= ?", CLONE_BASE);
        jdbc.update("DELETE FROM opportunity_conditions WHERE version_id >= ?", CLONE_BASE);
        jdbc.update("DELETE FROM opportunity_versions WHERE id >= ?", CLONE_BASE);
        jdbc.update("DELETE FROM opportunities WHERE id >= ?", CLONE_BASE);
    }

    /** 복제본 행이 템플릿과 같은지 (행 수, 표본 복제본의 jsonb 포함 행 내용) */
    void verifyClones(Map<String, Template> templates, Map<String, List<Long>> idsByTemplate, int size) {
        assertThat(count("SELECT count(*) FROM opportunities WHERE current_version_id IS NOT NULL")).isEqualTo(size);
        for (String table : List.of("opportunity_conditions", "application_windows", "opportunity_regions")) {
            long expected = 0;
            for (Map.Entry<String, List<Long>> e : idsByTemplate.entrySet()) {
                expected += (long) count("SELECT count(*) FROM " + table + " WHERE version_id = "
                        + templates.get(e.getKey()).versionId()) * e.getValue().size();
            }
            assertThat((long) count("SELECT count(*) FROM " + table + " WHERE version_id >= " + CLONE_BASE))
                    .as("%s 행 수", table).isEqualTo(expected);
        }
        for (Map.Entry<String, List<Long>> e : idsByTemplate.entrySet()) {
            Template t = templates.get(e.getKey());
            List<Long> ids = e.getValue();
            for (Long cloneId : List.of(ids.get(0), ids.get(ids.size() / 2), ids.get(ids.size() - 1))) {
                assertThat(rows(cloneId)).as("%s 복제본 %d 행", t.key(), cloneId).isEqualTo(rows(t.versionId()));
            }
        }
    }

    private List<Map<String, Object>> rows(long versionId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("SELECT 'C' AS k, condition_type, requirement_type, condition_field, "
                + "expression::text AS expression, evidence::text AS evidence, validation_status "
                + "FROM opportunity_conditions WHERE version_id = ? ORDER BY 2, 3, 4, 5", versionId));
        rows.addAll(jdbc.queryForList("SELECT 'W' AS k, period_type, start_date, end_date, period_text, "
                + "scope::text AS scope, validation_status FROM application_windows WHERE version_id = ? ORDER BY 2, 3, 4", versionId));
        rows.addAll(jdbc.queryForList("SELECT 'R' AS k, region_code, region_role "
                + "FROM opportunity_regions WHERE version_id = ? ORDER BY 2, 3", versionId));
        return rows;
    }

    int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
