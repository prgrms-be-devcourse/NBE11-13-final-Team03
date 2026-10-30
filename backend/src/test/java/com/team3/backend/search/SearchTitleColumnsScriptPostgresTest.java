package com.team3.backend.search;

import org.junit.jupiter.api.Tag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 검색 제목 생성 컬럼 관리자 SQL(database/scripts/search-title-columns*.sql)을 일회용 컨테이너에서 실행한다.
 * 공고 테이블은 현재 운영과 같은 형태(생성 컬럼 없음)로 만들고 시작한다.
 */
@Testcontainers
@Tag("postgres")
class SearchTitleColumnsScriptPostgresTest {

    private static final Path SCRIPTS = Path.of("../database/scripts");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("search_title_script_disposable")
            .withCopyFileToContainer(MountableFile.forHostPath(SCRIPTS.resolve("search-title-columns-check.sql")),
                    "/scripts/search-title-columns-check.sql");

    private Connection connection;

    @BeforeEach
    void prepareLegacySchema() throws Exception {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='application_user') THEN CREATE ROLE application_user; END IF; END $$");
        execute("DROP SCHEMA IF EXISTS application CASCADE");
        execute("CREATE SCHEMA application AUTHORIZATION application_user");
        execute("SET ROLE application_user");
        execute("SET search_path TO application, pg_catalog");
        execute(LegacyOpportunities.CREATE_TABLE);
        execute(LegacyOpportunities.INSERT_ROWS);
    }

    @AfterEach
    void closeConnection() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void addsGeneratedColumnsWithoutChangingExistingColumnsOrRows() throws Exception {
        String before = scalar(LegacyOpportunities.ORIGINAL_COLUMNS_HASH);

        execute(script("search-title-columns.sql"));

        assertThat(columnDefinitions()).containsExactly(
                "search_title_norm|s|text|lower(NORMALIZE(title, NFKC))",
                "search_title_strip|s|text|regexp_replace(lower(NORMALIZE(title, NFKC)), '[\\s·・‧ᆞ\\-]'::text, ''::text, 'g'::text)");
        assertThat(scalar(LegacyOpportunities.ORIGINAL_COLUMNS_HASH)).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM opportunities WHERE search_title_norm IS DISTINCT FROM lower(normalize(title, NFKC))"
                + " OR search_title_strip IS DISTINCT FROM regexp_replace(lower(normalize(title, NFKC)), '[\\s·・‧ᆞ\\-]', '', 'g')"))
                .isEqualTo("0");
        for (String[] row : LegacyOpportunities.EXPECTED) {
            assertThat(scalar("SELECT search_title_norm || '|' || search_title_strip FROM opportunities WHERE title = '"
                    + row[0].replace("'", "''") + "'"))
                    .as(row[0]).isEqualTo(row[1] + "|" + row[2]);
        }
    }

    @Test
    void repeatedRunLeavesTheTableUntouched() throws Exception {
        execute(script("search-title-columns.sql"));
        String fileNode = scalar("SELECT relfilenode FROM pg_class WHERE oid = 'application.opportunities'::regclass");

        execute(script("search-title-columns.sql"));

        assertThat(scalar("SELECT relfilenode FROM pg_class WHERE oid = 'application.opportunities'::regclass"))
                .isEqualTo(fileNode);
        assertThat(columnDefinitions()).hasSize(2);
    }

    @Test
    void regularColumnCreatedByHibernateFirstStopsTheScriptWithoutDroppingIt() throws Exception {
        // backend가 스크립트보다 먼저 ddl-auto:update로 떴을 때 Hibernate가 만드는 형태
        execute("ALTER TABLE opportunities ADD COLUMN search_title_norm text");

        assertThatThrownBy(() -> execute(script("search-title-columns.sql")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("already exists as a regular column")
                .hasMessageContaining("Not dropping it automatically");
        execute("ROLLBACK");

        // 일반 컬럼은 그대로 남고, 다른 컬럼도 추가되지 않았다
        assertThat(columnDefinitions()).containsExactly("search_title_norm||text|");
        assertThatThrownBy(() -> execute(script("rollback-search-title-columns.sql")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Refusing to drop it");
        execute("ROLLBACK");
        assertThat(columnDefinitions()).containsExactly("search_title_norm||text|");
    }

    @Test
    void generatedColumnWithDifferentExpressionStopsTheScript() throws Exception {
        execute("ALTER TABLE opportunities ADD COLUMN search_title_norm text GENERATED ALWAYS AS (lower(title)) STORED");

        assertThatThrownBy(() -> execute(script("search-title-columns.sql")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("already exists with a different definition");
        execute("ROLLBACK");

        assertThat(columnDefinitions()).containsExactly("search_title_norm|s|text|lower((title)::text)");
    }

    @Test
    void rollbackRemovesOnlyTheGeneratedColumnsAndCanBeRepeated() throws Exception {
        String before = scalar(LegacyOpportunities.ORIGINAL_COLUMNS_HASH);
        execute(script("search-title-columns.sql"));

        execute(script("rollback-search-title-columns.sql"));
        execute(script("rollback-search-title-columns.sql"));

        assertThat(columnDefinitions()).isEmpty();
        assertThat(scalar(LegacyOpportunities.ORIGINAL_COLUMNS_HASH)).isEqualTo(before);
        // 롤백 후 다시 적용할 수 있다
        execute(script("search-title-columns.sql"));
        assertThat(columnDefinitions()).hasSize(2);
    }

    @Test
    void scriptsRefuseToRunAsAnotherRole() throws Exception {
        execute("RESET ROLE");

        assertThatThrownBy(() -> execute(script("search-title-columns.sql")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Run search-title-columns.sql as application_user");
        assertThatThrownBy(() -> execute(script("rollback-search-title-columns.sql")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Run rollback-search-title-columns.sql as application_user");
        execute("SET ROLE application_user");
        assertThat(columnDefinitions()).isEmpty();
    }

    @Test
    void checkScriptReportsOkAfterApplyAndSkipsValueCheckWithoutColumns() throws Exception {
        // 점검 스크립트는 psql 메타 명령(\echo, \gset, \if)을 쓰므로 컨테이너 안 psql로 실행한다
        assertThat(checkScript()).contains("CHECK: 누락이거나 정의가 다르다").contains("건너뜀");

        execute(script("search-title-columns.sql"));

        String output = checkScript();
        assertThat(output).contains("OK: 두 생성 컬럼 모두 기대한 정의");
        assertThat(output).containsPattern("\\|\\s*0\\s*\\|\\s*0\\s*\\|\\s*0\\s*\\n");
    }

    private String checkScript() throws Exception {
        ExecResult result = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(),
                "-d", POSTGRES.getDatabaseName(), "-X", "-v", "ON_ERROR_STOP=1",
                "-c", "SET ROLE application_user", "-f", "/scripts/search-title-columns-check.sql");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return result.getStdout();
    }

    private List<String> columnDefinitions() throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT a.attname || '|' || a.attgenerated::text || '|' || format_type(a.atttypid, a.atttypmod)
                       || '|' || coalesce(pg_get_expr(d.adbin, d.adrelid), '')
                FROM pg_attribute a
                LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE a.attrelid = 'application.opportunities'::regclass
                  AND a.attname LIKE 'search_title%' AND NOT a.attisdropped
                ORDER BY a.attname""")) {
            List<String> definitions = new java.util.ArrayList<>();
            while (rows.next()) definitions.add(rows.getString(1));
            return definitions;
        }
    }

    private String script(String name) throws Exception {
        return Files.readString(SCRIPTS.resolve(name));
    }

    private String scalar(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
