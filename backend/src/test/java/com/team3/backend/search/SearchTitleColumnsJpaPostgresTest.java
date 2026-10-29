package com.team3.backend.search;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.opportunity.domain.repository.OpportunitySourceRepository;
import com.team3.backend.opportunity.normalization.dto.TrainingCourseRaw;
import com.team3.backend.opportunity.normalization.dto.TrainingNormalizationCommand;
import com.team3.backend.opportunity.service.TrainingCourseNormalizationService;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 운영 배포 순서를 그대로 재현한다.
 * 기존 공고 테이블·데이터 -> search-title-columns.sql 적용 -> backend가 ddl-auto:update로 기동.
 * Hibernate보다 스크립트가 먼저 돌아야 하므로 Spring 컨텍스트 전에 정적 초기화에서 스키마를 준비한다.
 * (ddl-auto:create를 쓰는 다른 Postgres 테스트는 같은 이름의 일반 컬럼을 만들므로 이 검증에 쓸 수 없다.)
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SearchTitleColumnsJpaPostgresTest {

    private static final String PASSWORD = "search-title-test-password";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("search_title_jpa_disposable");

    private static final String HASH_BEFORE_MIGRATION;
    private static final String FILE_NODE_AFTER_MIGRATION;

    static {
        POSTGRES.start();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE application_user LOGIN PASSWORD '" + PASSWORD + "'");
            statement.execute("CREATE SCHEMA application AUTHORIZATION application_user");
            statement.execute("SET ROLE application_user");
            statement.execute("SET search_path TO application, pg_catalog");
            statement.execute(LegacyOpportunities.CREATE_TABLE);
            statement.execute(LegacyOpportunities.INSERT_ROWS);
            HASH_BEFORE_MIGRATION = single(statement, LegacyOpportunities.ORIGINAL_COLUMNS_HASH);
            statement.execute(Files.readString(Path.of("../database/scripts/search-title-columns.sql")));
            FILE_NODE_AFTER_MIGRATION = single(statement,
                    "SELECT relfilenode FROM pg_class WHERE oid = 'application.opportunities'::regclass");
        } catch (Exception e) {
            throw new IllegalStateException("생성 컬럼 사전 준비 실패", e);
        }
    }

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName() + "?currentSchema=application");
        registry.add("spring.datasource.username", () -> "application_user");
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        // 운영 backend와 같은 설정. 이미 있는 생성 컬럼을 Hibernate가 건드리지 않아야 한다
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TrainingCourseNormalizationService trainingCourseNormalizationService;

    @Autowired
    private OpportunitySourceRepository opportunitySourceRepository;

    @Test
    void hibernateUpdateKeepsGeneratedColumnsAndMappingValidates() throws Exception {
        assertThat(jdbcTemplate.queryForList("""
                SELECT a.attname || '|' || a.attgenerated::text || '|' || format_type(a.atttypid, a.atttypmod)
                       || '|' || coalesce(pg_get_expr(d.adbin, d.adrelid), '')
                FROM pg_attribute a
                LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE a.attrelid = 'application.opportunities'::regclass
                  AND a.attname LIKE 'search_title%' AND NOT a.attisdropped
                ORDER BY a.attname""", String.class)).containsExactly(
                "search_title_norm|s|text|lower(NORMALIZE(title, NFKC))",
                "search_title_strip|s|text|regexp_replace(lower(NORMALIZE(title, NFKC)), '[\\s·・‧ᆞ\\-]'::text, ''::text, 'g'::text)");
        // ddl-auto:update가 opportunities를 다시 쓰거나 컬럼을 더하지 않았다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT relfilenode::text FROM pg_class WHERE oid = 'application.opportunities'::regclass", String.class))
                .isEqualTo(FILE_NODE_AFTER_MIGRATION);
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'application' AND table_name = 'opportunities' ORDER BY column_name""", String.class))
                .containsExactly("category", "created_at", "current_version_id", "detail_category", "detail_url", "id",
                        "opportunity_type", "search_title_norm", "search_title_strip", "sub_category", "title",
                        "updated_at");
        // ddl-auto:validate로 바꿔도 매핑과 실제 스키마가 맞는다
        entityManagerFactory.getSchemaManager().validate();
    }

    @Test
    void existingRowsKeepOriginalValuesAndAreReadWithNormalizedTitles() {
        assertThat(jdbcTemplate.queryForObject(LegacyOpportunities.ORIGINAL_COLUMNS_HASH, String.class))
                .isEqualTo(HASH_BEFORE_MIGRATION);

        List<Opportunity> legacy = entityManager.createQuery(
                "select o from Opportunity o where o.detailUrl = 'https://example.invalid/legacy' order by o.id",
                Opportunity.class).getResultList();
        assertThat(legacy).hasSize(LegacyOpportunities.EXPECTED.size());
        for (int i = 0; i < legacy.size(); i++) {
            String[] expected = LegacyOpportunities.EXPECTED.get(i);
            assertThat(legacy.get(i).getTitle()).isEqualTo(expected[0]);
            assertThat(legacy.get(i).getSearchTitleNorm()).as(expected[0]).isEqualTo(expected[1]);
            assertThat(legacy.get(i).getSearchTitleStrip()).as(expected[0]).isEqualTo(expected[2]);
            assertThat(legacy.get(i).getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 0));
        }
    }

    @Test
    void jpaInsertAndTitleUpdateLetDatabaseComputeColumns() {
        Opportunity opportunity = opportunity("ＡＩ 청년 월세ㆍ지원 (2026년)", OpportunityType.YOUTH_POLICY);
        entityManager.flush();
        // DB가 계산한 값은 다시 읽어야 보인다 (엔티티에 반영하지 않는다)
        assertThat(opportunity.getSearchTitleNorm()).isNull();
        entityManager.clear();

        Opportunity inserted = entityManager.find(Opportunity.class, opportunity.getId());
        assertThat(inserted.getSearchTitleNorm()).isEqualTo("ai 청년 월세ᆞ지원 (2026년)");
        assertThat(inserted.getSearchTitleStrip()).isEqualTo("ai청년월세지원(2026년)");

        inserted.updateSummary("[원패스] 파이썬(Python) 프로그래밍Ⅱ", "교육", "정보통신", null);
        entityManager.flush();
        entityManager.clear();

        Opportunity updated = entityManager.find(Opportunity.class, opportunity.getId());
        assertThat(updated.getSearchTitleNorm()).isEqualTo("[원패스] 파이썬(python) 프로그래밍ii");
        assertThat(updated.getSearchTitleStrip()).isEqualTo("[원패스]파이썬(python)프로그래밍ii");

        // 제목이 아닌 컬럼만 바꾸는 갱신(현재 버전 교체)에서도 값은 그대로다
        OpportunityVersion next = OpportunityVersion.builder()
                .opportunity(updated).versionNo(2).normalizedSnapshot(Map.of("title", updated.getTitle()))
                .personalizationStatus(PersonalizationStatus.UNKNOWN).normalizationVersion("1").build();
        entityManager.persist(next);
        updated.changeCurrentVersion(next);
        entityManager.flush();
        entityManager.clear();

        Opportunity versioned = entityManager.find(Opportunity.class, opportunity.getId());
        assertThat(versioned.getCurrentVersion().getId()).isEqualTo(next.getId());
        assertThat(versioned.getSearchTitleNorm()).isEqualTo("[원패스] 파이썬(python) 프로그래밍ii");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM opportunities
                WHERE search_title_norm IS DISTINCT FROM lower(normalize(title, NFKC))
                   OR search_title_strip IS DISTINCT FROM regexp_replace(lower(normalize(title, NFKC)), '[\\s·・‧ᆞ\\-]', '', 'g')""",
                Long.class)).isZero();
    }

    @Test
    void existingNormalizationWritePathFillsColumns() {
        Long sourceId = 990101L;
        TrainingCourseRaw raw = TrainingCourseRaw.builder()
                .trprId("PG-TITLE-COLUMNS").trprDegr("1").title("Ｅｘｃｅｌ 실무  과정")
                .ncsCd("20010202").build();

        assertThat(trainingCourseNormalizationService.normalizePage(new TrainingNormalizationCommand(
                sourceId, List.of(raw), LocalDateTime.now())).created()).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();

        Opportunity saved = opportunitySourceRepository
                .findBySourceIdAndExternalId(sourceId, "PG-TITLE-COLUMNS:1").orElseThrow().getOpportunity();
        assertThat(saved.getSearchTitleNorm()).isEqualTo("excel 실무  과정");
        assertThat(saved.getSearchTitleStrip()).isEqualTo("excel실무과정");
    }

    @Test
    void searchApiBehavesAsBeforeOnTopOfGeneratedColumns() throws Exception {
        Opportunity first = opportunity("PG title columns first", OpportunityType.YOUTH_POLICY);
        Opportunity second = opportunity("PG title columns second", OpportunityType.VOCATIONAL_TRAINING);
        opportunity("PG other", OpportunityType.YOUTH_POLICY);
        entityManager.flush();
        entityManager.clear();

        // 검색은 아직 생성 컬럼을 쓰지 않는다. 기존과 같이 lower(title) LIKE + LATEST(created_at DESC, id DESC)
        MvcResult result = mockMvc.perform(get("/api/opportunities").param("keyword", "pg TITLE columns"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(body, "$.totalElements")).isEqualTo(2);
        List<Number> ids = JsonPath.read(body, "$.content[*].opportunityId");
        assertThat(ids.stream().map(Number::longValue).toList()).containsExactly(second.getId(), first.getId());
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

    private static String single(java.sql.Statement statement, String sql) throws Exception {
        try (var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
