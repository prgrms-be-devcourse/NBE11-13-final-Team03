package com.team3.backend.search.controller;

import com.jayway.jsonpath.JsonPath;
import com.team3.backend.opportunity.domain.entity.ApplicationWindow;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.entity.TrainingCourseDetail;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.domain.entity.YouthPolicyDetail;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 키워드 후보 확장(B1)을 PostgreSQL에서 검증한다.
 * 기대 후보는 B1 규칙(검색 품질 실험의 SQL 프로토타입)으로 정한 것이며, 기존 제목 부분 일치 결과는 항상 포함된다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpportunitySearchCandidatePostgresTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("yon_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.jpa.properties.hibernate.hbm2ddl.import_files",
                () -> "search-title-columns-hibernate-create.sql");
        registry.add("spring.jpa.properties.hibernate.hbm2ddl.halt_on_error", () -> "true");
        // import 파일은 기본 문자셋으로 읽힌다. 정규식의 가운뎃점(·・‧ᆞ)이 깨지지 않게 명시한다
        registry.add("spring.jpa.properties.hibernate.hbm2ddl.charset_name", () -> "UTF-8");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void importFixtureCreatesTheSameGeneratedColumnsAsTheMigrationScript() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT a.attname || '|' || a.attgenerated::text || '|' || format_type(a.atttypid, a.atttypmod)
                       || '|' || coalesce(pg_get_expr(d.adbin, d.adrelid), '')
                FROM pg_attribute a
                LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE a.attrelid = 'opportunities'::regclass
                  AND a.attname LIKE 'search_title%' AND NOT a.attisdropped
                ORDER BY a.attname""", String.class)).containsExactly(
                "search_title_norm|s|text|lower(NORMALIZE(title, NFKC))",
                "search_title_strip|s|text|regexp_replace(lower(NORMALIZE(title, NFKC)), '[\\s·・‧ᆞ\\-]'::text, ''::text, 'g'::text)");
    }

    @Test
    void spacingSynonymsAndSeparatorsExtendTitleContains() throws Exception {
        Opportunity spaced = opportunity("청년 월세 지원", OpportunityType.YOUTH_POLICY);
        Opportunity joined = opportunity("청년월세 한시 특별지원", OpportunityType.YOUTH_POLICY);
        Opportunity middleDot = opportunity("청년ㆍ월세 상담", OpportunityType.YOUTH_POLICY);
        Opportunity onlyOne = opportunity("청년 주거 상담", OpportunityType.YOUTH_POLICY);
        Opportunity english = opportunity("[원패스] Python 기초", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity korean = opportunity("파이썬 데이터분석", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity hyphen = opportunity("React-Native 앱 개발", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity fullWidth = opportunity("ＥＸＣＥＬ 실무", OpportunityType.VOCATIONAL_TRAINING);
        flush();

        // 기존 제목 부분 일치는 spaced 하나뿐이다. 토큰 AND가 띄어쓰기·가운뎃점 차이를 넘는다
        assertThat(ids(search("청년 월세"))).containsExactly(middleDot.getId(), joined.getId(), spaced.getId());
        // 붙여 쓴 검색어도 공백·가운뎃점을 지운 제목과 비교한다
        assertThat(ids(search("청년월세"))).containsExactly(middleDot.getId(), joined.getId(), spaced.getId());
        // 동의어 그룹은 어느 표기로 찾아도 같은 후보다
        assertThat(ids(search("파이썬"))).containsExactly(korean.getId(), english.getId());
        assertThat(ids(search("PYTHON"))).containsExactly(korean.getId(), english.getId());
        assertThat(ids(search("react native"))).containsExactly(hyphen.getId());
        assertThat(ids(search("리액트 네이티브"))).isEmpty();
        assertThat(ids(search("엑셀"))).containsExactly(fullWidth.getId());
        assertThat(ids(search("청년 상담"))).containsExactly(onlyOne.getId(), middleDot.getId());
    }

    @Test
    void shortLatinTokenRequiresWordBoundaryOutsideTitleContains() throws Exception {
        Opportunity leading = opportunity("AI 마케팅 실무", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity reordered = opportunity("마케팅 AI 활용", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity attached = opportunity("생성형AI 마케팅", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity email = opportunity("Email 마케팅", OpportunityType.VOCATIONAL_TRAINING);
        flush();

        // email은 ai를 영문 단어 안에 품고 있어 토큰 조건으로는 걸리지 않는다
        assertThat(ids(search("ai 마케팅").param("sort", "LATEST"))).containsExactly(attached.getId(), reordered.getId(), leading.getId());
        // 기존 제목 부분 일치는 그대로라 단일 검색어 ai에는 email도 남는다
        assertThat(ids(search("ai"))).contains(email.getId());
    }

    @Test
    void policyKeywordsPrefixAndTrainingTargetExtendCandidatesButNcsDoesNot() throws Exception {
        Opportunity policy = opportunity("청년 생활 안정 사업", OpportunityType.YOUTH_POLICY);
        policyDetail(policy, "주거지원,월세,생활비");
        Opportunity infix = opportunity("청년 생활 보조 사업", OpportunityType.YOUTH_POLICY);
        policyDetail(infix, "긴급월세");
        Opportunity training = opportunity("회계 실무 과정", OpportunityType.VOCATIONAL_TRAINING);
        trainingDetail(training, "국민내일배움카드(일반)", null);
        Opportunity ncs = opportunity("세무 기초 과정", OpportunityType.VOCATIONAL_TRAINING);
        ncs.updateSummary(ncs.getTitle(), "교육･직업훈련", "정보통신", "정보기술", null);
        Opportunity jamo = opportunity("청년 생활 정착 사업", OpportunityType.YOUTH_POLICY);
        policyDetail(jamo, "생활ㆍ정착금");
        flush();

        // keywords도 제목과 같은 정규화(NFKC로 ㆍ->ᆞ, 가운뎃점 제거)를 거친다
        assertThat(ids(search("생활정착금"))).containsExactly(jamo.getId());

        // keywords 항목의 시작에만 맞는다 (긴급월세의 '월세'는 항목 중간이라 제외)
        assertThat(ids(search("월세"))).containsExactly(policy.getId());
        assertThat(ids(search("생활비 청년"))).containsExactly(policy.getId());
        // 범용어·1자 표기는 keywords로 비교하지 않는다
        assertThat(ids(search("지원"))).isEmpty();
        assertThat(ids(search("내일배움카드"))).containsExactly(training.getId());
        // 4자 미만 표기는 훈련구분으로 비교하지 않는다
        assertThat(ids(search("카드"))).isEmpty();
        // NCS 분류는 후보 조건이 아니다
        assertThat(ids(search("정보기술"))).isEmpty();
    }

    @Test
    void trainingTargetsAreMatchedOnDatabaseNormalizedValuesAndNewValuesApplyImmediately() throws Exception {
        Opportunity fullWidth = opportunity("웹 개발 실무", OpportunityType.VOCATIONAL_TRAINING);
        trainingDetail(fullWidth, "Ｋ－디지털　트레이닝", null);
        Opportunity hyphen = opportunity("데이터 분석 실무", OpportunityType.VOCATIONAL_TRAINING);
        trainingDetail(hyphen, "K-디지털 기초역량훈련", null);
        Opportunity noTarget = opportunity("클라우드 실무", OpportunityType.VOCATIONAL_TRAINING);
        trainingDetail(noTarget, null, null);
        Opportunity policy = opportunity("디지털 역량 지원", OpportunityType.YOUTH_POLICY);
        policyDetail(policy, null);
        flush();

        // 원문 표기가 달라도 DB 정규화 값(k디지털트레이닝, k디지털기초역량훈련)으로 판정한다
        assertThat(ids(search("k디지털"))).containsExactly(hyphen.getId(), fullWidth.getId());
        assertThat(ids(search("K-디지털 트레이닝"))).containsExactly(fullWidth.getId());
        assertThat(ids(search("기초역량훈련 실무"))).containsExactly(hyphen.getId());
        // 범용어(훈련)·4자 미만(디지털) 표기는 훈련구분으로 비교하지 않는다. 정책은 훈련구분 비교 대상이 아니다
        assertThat(ids(search("훈련"))).isEmpty();
        assertThat(ids(search("디지털"))).containsExactly(policy.getId());

        // 훈련구분 값 목록을 캐시하지 않으므로 새 값은 다음 검색부터 바로 반영된다
        assertThat(ids(search("혼합훈련"))).isEmpty();
        Opportunity added = opportunity("빅데이터 실무", OpportunityType.VOCATIONAL_TRAINING);
        trainingDetail(added, "스마트혼합훈련", null);
        flush();
        assertThat(ids(search("혼합훈련"))).containsExactly(added.getId());
    }

    @Test
    void phraseModeAndSpecialCharacters() throws Exception {
        Opportunity phrase = opportunity("데이터분석 실무과정 온라인수료 특강", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity reordered = opportunity("온라인 수료 데이터 분석 실무 과정", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity bang = opportunity("공지!!! 신청 안내", OpportunityType.YOUTH_POLICY);
        Opportunity percent = opportunity("교육비 100% 환급", OpportunityType.VOCATIONAL_TRAINING);
        opportunity("교육비 1000원 환급", OpportunityType.VOCATIONAL_TRAINING);
        flush();

        // 토큰 6개: 토큰 AND 대신 공백을 무시한 전체 구문만 더한다 (순서가 다른 제목은 제외)
        assertThat(ids(search("데이터 분석 실무 과정 온라인 수료"))).containsExactly(phrase.getId());
        assertThat(ids(search("데이터 분석 실무 과정 온라인").param("sort", "LATEST"))).containsExactly(reordered.getId(), phrase.getId());
        // 구분자만 입력하면 기존 제목 부분 일치만 쓴다
        assertThat(ids(search("!!!"))).containsExactly(bang.getId());
        assertThat(ids(search("100%"))).containsExactly(percent.getId());
    }

    @Test
    void keywordCandidatesCombineWithExistingFiltersAndExactPageCounts() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Opportunity titleMatch = opportunity("청년 월세 지원", OpportunityType.YOUTH_POLICY);
        Opportunity joined = opportunity("청년월세 특별지원", OpportunityType.YOUTH_POLICY);
        Opportunity keywordOnly = opportunity("청년 생활 안정", OpportunityType.YOUTH_POLICY);
        policyDetail(keywordOnly, "월세,청년");
        Opportunity otherCategory = opportunity("청년월세 대출", OpportunityType.YOUTH_POLICY);
        Opportunity closed = opportunity("청년 월세 지난 모집", OpportunityType.YOUTH_POLICY);
        Opportunity training = opportunity("청년월세 교육", OpportunityType.VOCATIONAL_TRAINING);
        for (Opportunity policy : List.of(titleMatch, joined, keywordOnly, otherCategory, closed)) {
            policy.updateSummary(policy.getTitle(), policy == otherCategory ? "금융" : "주거", "주거", null);
            window(policy, policy == closed ? today.minusDays(10) : today.minusDays(1),
                    policy == closed ? today.minusDays(1) : today.plusDays(5));
        }
        flush();

        List<Long> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = body(search("청년 월세").param("sort", "LATEST").param("type", "YOUTH_POLICY").param("category", "주거")
                    .param("applicationStatus", "OPEN").param("page", String.valueOf(page)).param("size", "1"));
            assertThat(JsonPath.<Integer>read(body, "$.totalElements")).isEqualTo(3);
            assertThat(JsonPath.<Integer>read(body, "$.totalPages")).isEqualTo(3);
            paged.addAll(ids(body));
        }
        assertThat(paged).containsExactly(keywordOnly.getId(), joined.getId(), titleMatch.getId());
        assertThat(new HashSet<>(paged)).hasSize(3);
        assertThat(ids(search("청년 월세"))).containsExactlyInAnyOrder(titleMatch.getId(), joined.getId(),
                keywordOnly.getId(), otherCategory.getId(), closed.getId(), training.getId());
    }

    @Test
    void explicitSortsOrderExpandedCandidatesByTheirExistingRules() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Opportunity first = opportunity("청년 월세 지원", OpportunityType.YOUTH_POLICY);
        Opportunity second = opportunity("청년월세 특별지원", OpportunityType.YOUTH_POLICY);
        Opportunity third = opportunity("월세 청년 상담", OpportunityType.YOUTH_POLICY);
        window(first, today.minusDays(1), today.plusDays(9));
        window(second, today.minusDays(1), today.plusDays(3));
        window(third, today.minusDays(1), today.plusDays(6));
        Opportunity early = opportunity("파이썬 입문", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity late = opportunity("Python 심화", OpportunityType.VOCATIONAL_TRAINING);
        Opportunity unknown = opportunity("파이썬 실무", OpportunityType.VOCATIONAL_TRAINING);
        trainingDetail(early, null, today.plusDays(2));
        trainingDetail(late, null, today.plusDays(8));
        trainingDetail(unknown, null, null);
        flush();

        assertThat(ids(search("청년 월세").param("sort", "LATEST")))
                .containsExactly(third.getId(), second.getId(), first.getId());
        assertThat(ids(search("청년 월세").param("sort", "OLDEST")))
                .containsExactly(first.getId(), second.getId(), third.getId());
        assertThat(ids(search("청년 월세").param("type", "YOUTH_POLICY").param("sort", "DEADLINE_PRIORITY")))
                .containsExactly(second.getId(), third.getId(), first.getId());
        assertThat(ids(search("python").param("type", "VOCATIONAL_TRAINING").param("sort", "START_DATE_ASC")))
                .containsExactly(early.getId(), late.getId(), unknown.getId());
    }

    @Test
    void keywordSearchDefaultsToRelevanceAndExplicitSortsAreRespected() throws Exception {
        // 등록 순서를 관련도와 반대로 둬서 최신순과 관련도순이 구별되게 한다
        Opportunity exact = opportunity("청년 월세", OpportunityType.YOUTH_POLICY);            // tier 5 완전 일치
        Opportunity prefix = opportunity("청년 월세 지원사업", OpportunityType.YOUTH_POLICY);   // tier 4 접두
        Opportunity phrase = opportunity("서울시 청년 월세 지원", OpportunityType.YOUTH_POLICY); // tier 3 구문
        Opportunity joined = opportunity("서울시 청년월세 지원", OpportunityType.YOUTH_POLICY);  // tier 2 공백 무시 구문
        Opportunity tokens = opportunity("월세 지원 청년 대상", OpportunityType.YOUTH_POLICY);   // tier 1 모든 토큰
        flush();
        List<Long> relevance = List.of(exact.getId(), prefix.getId(), phrase.getId(), joined.getId(), tokens.getId());
        List<Long> latest = List.of(tokens.getId(), joined.getId(), phrase.getId(), prefix.getId(), exact.getId());

        assertThat(ids(search("청년 월세"))).containsExactlyElementsOf(relevance);
        assertThat(ids(search("청년 월세").param("sort", "RELEVANCE"))).containsExactlyElementsOf(relevance);
        assertThat(ids(search("청년 월세").param("sort", "LATEST"))).containsExactlyElementsOf(latest);
        assertThat(ids(search("청년 월세").param("sort", "OLDEST"))).containsExactlyElementsOf(relevance);
        // 토큰이 모두 범용어라 보너스 신호가 없어도(모두 tier 1, bonus 0) 관련도순이 실패하지 않는다
        assertThat(ids(search("청년 지원")))
                .containsExactly(tokens.getId(), joined.getId(), phrase.getId(), prefix.getId());
        // 검색어가 없으면 생략·RELEVANCE 모두 최신순이다
        assertThat(ids(get("/api/opportunities"))).containsExactlyElementsOf(latest);
        assertThat(ids(get("/api/opportunities").param("sort", "RELEVANCE"))).containsExactlyElementsOf(latest);
        assertThat(ids(get("/api/opportunities").param("keyword", " ").param("sort", "RELEVANCE")))
                .containsExactlyElementsOf(latest);
    }

    @Test
    void bonusAndApplicationStatusOnlyBreakTiesWithinRelevance() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Opportunity closedPrefix = opportunity("월세 상담 청년", OpportunityType.YOUTH_POLICY);  // tier 4, 마감
        window(closedPrefix, today.minusDays(10), today.minusDays(1));
        Opportunity closedKeyword = opportunity("청년 월세 지원", OpportunityType.YOUTH_POLICY); // tier 3, keywords +30, 마감
        policyDetail(closedKeyword, "월세,주거지원");
        window(closedKeyword, today.minusDays(10), today.minusDays(1));
        Opportunity open = opportunity("청년 월세 상담", OpportunityType.YOUTH_POLICY);          // tier 3, 모집 중
        window(open, today.minusDays(1), today.plusDays(5));
        Opportunity upcoming = opportunity("청년 월세 설명회", OpportunityType.YOUTH_POLICY);    // tier 3, 모집 예정
        window(upcoming, today.plusDays(1), today.plusDays(5));
        Opportunity unknown = opportunity("청년 월세 안내", OpportunityType.YOUTH_POLICY);       // tier 3, 상태 미상
        Opportunity closed = opportunity("청년 월세 공고", OpportunityType.YOUTH_POLICY);        // tier 3, 마감
        window(closed, today.minusDays(10), today.minusDays(1));
        Opportunity always = opportunity("청년 월세 상시", OpportunityType.YOUTH_POLICY);        // tier 3, 상시 모집
        entityManager.persist(ApplicationWindow.builder().version(always.getCurrentVersion())
                .periodType(PeriodType.ALWAYS).validationStatus(ValidationStatus.VALID).build());
        flush();

        // tier가 높으면 마감이어도 먼저, 같은 tier에서는 bonus가 먼저, 모집 상태는 tier·bonus가 같을 때만 쓴다
        // OPEN·ALWAYS_OPEN(같은 우선순위) 안에서는 최신 등록순이다
        assertThat(ids(search("월세"))).containsExactly(closedPrefix.getId(), closedKeyword.getId(),
                always.getId(), open.getId(), upcoming.getId(), unknown.getId(), closed.getId());
    }

    @Test
    void upcomingCoursesShareTheOpenPriorityAndTrainingTargetAddsBonus() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Opportunity target = opportunity("파이썬 실무", OpportunityType.VOCATIONAL_TRAINING);
        // 파이썬(3자)은 훈련구분 비교 대상이 아니고 동의어 python(6자)이 비교된다
        trainingDetail(target, "Python 집중훈련", today.minusDays(3));
        Opportunity started = opportunity("파이썬 기초", OpportunityType.VOCATIONAL_TRAINING);
        trainingDetail(started, null, today.minusDays(3));
        Opportunity startsToday = opportunity("파이썬 입문", OpportunityType.VOCATIONAL_TRAINING);
        trainingDetail(startsToday, null, today);
        Opportunity noDetail = opportunity("파이썬 심화", OpportunityType.VOCATIONAL_TRAINING);
        flush();

        // 모두 tier 4(접두). 훈련구분 일치(+40)가 먼저, 다음은 개강 예정(오늘 포함)이 상태 미상보다 먼저다
        assertThat(ids(search("파이썬"))).containsExactly(target.getId(), startsToday.getId(),
                noDetail.getId(), started.getId());
    }

    @Test
    void relevancePagesKeepCountsAndCoverEveryCandidateOnce() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        List<Long> created = new ArrayList<>();
        for (String title : List.of("청년 월세", "청년월세 지원", "서울 청년 월세", "월세 청년", "청년 월세 상담",
                "청년 주거 월세", "청년 월세 대출")) {
            Opportunity opportunity = opportunity(title, OpportunityType.YOUTH_POLICY);
            opportunity.updateSummary(title, "주거", "주거", null);
            window(opportunity, today.minusDays(1), today.plusDays(3));
            created.add(opportunity.getId());
        }
        opportunity("청년 월세 다른 분류", OpportunityType.YOUTH_POLICY);
        flush();

        List<Long> full = ids(search("청년 월세").param("category", "주거").param("applicationStatus", "OPEN")
                .param("size", "100"));
        List<Long> paged = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            String body = body(search("청년 월세").param("category", "주거").param("applicationStatus", "OPEN")
                    .param("page", String.valueOf(page)).param("size", "2"));
            assertThat(JsonPath.<Integer>read(body, "$.totalElements")).isEqualTo(7);
            assertThat(JsonPath.<Integer>read(body, "$.totalPages")).isEqualTo(4);
            paged.addAll(ids(body));
        }
        assertThat(paged).containsExactlyElementsOf(full);
        assertThat(full).containsExactlyInAnyOrderElementsOf(created);
        assertThat(JsonPath.<Integer>read(body(search("청년 월세").param("category", "주거")
                .param("applicationStatus", "OPEN").param("sort", "LATEST")), "$.totalElements")).isEqualTo(7);
    }

    private MockHttpServletRequestBuilder search(String keyword) {
        return get("/api/opportunities").param("keyword", keyword);
    }

    private List<Long> ids(MockHttpServletRequestBuilder request) throws Exception {
        return ids(body(request));
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static List<Long> ids(String body) {
        List<Number> ids = JsonPath.read(body, "$.content[*].opportunityId");
        assertThat(JsonPath.<Integer>read(body, "$.totalElements")).isGreaterThanOrEqualTo(ids.size());
        return ids.stream().map(Number::longValue).toList();
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    private Opportunity opportunity(String title, OpportunityType type) {
        Opportunity opportunity = Opportunity.builder().title(title).opportunityType(type).build();
        entityManager.persist(opportunity);
        OpportunityVersion version = OpportunityVersion.builder()
                .opportunity(opportunity).versionNo(1)
                .normalizedSnapshot(Map.of("title", title))
                .personalizationStatus(PersonalizationStatus.UNKNOWN)
                .normalizationVersion("1").build();
        entityManager.persist(version);
        opportunity.changeCurrentVersion(version);
        return opportunity;
    }

    private void policyDetail(Opportunity opportunity, String keywords) {
        entityManager.persist(YouthPolicyDetail.builder().version(opportunity.getCurrentVersion())
                .policyNo("PG-B1-" + opportunity.getId()).keywords(keywords).build());
    }

    private void trainingDetail(Opportunity opportunity, String trainingTargetName, LocalDate startDate) {
        entityManager.persist(TrainingCourseDetail.builder().version(opportunity.getCurrentVersion())
                .courseId("PG-B1-" + opportunity.getId()).courseRound(1)
                .trainingTargetName(trainingTargetName).trainingStartDate(startDate).build());
    }

    private void window(Opportunity opportunity, LocalDate start, LocalDate end) {
        entityManager.persist(ApplicationWindow.builder().version(opportunity.getCurrentVersion())
                .periodType(PeriodType.FIXED).startDate(start).endDate(end)
                .validationStatus(ValidationStatus.VALID).build());
    }
}
