package com.team3.backend.search;

import org.junit.jupiter.api.Tag;

import com.team3.backend.search.service.SearchTitleColumns;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 생성 컬럼 스크립트 없이 backend가 먼저 떠 Hibernate가 search_title_* 를 일반 컬럼으로 만든 상태를 재현한다.
 * 키워드 검색은 제목 부분 일치로 조용히 넘어가지 않고 실패해야 한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Tag("postgres")
class SearchTitleColumnsMissingPostgresTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("search_title_missing_disposable");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        // import 픽스처 없이 Hibernate가 만든 일반 컬럼 그대로 둔다
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SearchTitleColumns searchTitleColumns;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void keywordSearchFailsUntilGeneratedColumnsExist() throws Exception {
        assertThatThrownBy(() -> searchTitleColumns.verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("search-title-columns.sql")
                .hasMessageContaining("generatedColumns=0");
        mockMvc.perform(get("/api/opportunities").param("keyword", "청년 월세"))
                .andExpect(status().isInternalServerError());

        // 후보 확장을 쓰지 않는 검색은 영향이 없다
        mockMvc.perform(get("/api/opportunities")).andExpect(status().isOk());
        mockMvc.perform(get("/api/opportunities").param("keyword", "!!!")).andExpect(status().isOk());

        // 생성 컬럼을 갖추면 재기동 없이 다음 검색부터 통과한다 (실패는 기억하지 않는다)
        String fixture = new ClassPathResource("search-title-columns-hibernate-create.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbcTemplate.execute(fixture);
        mockMvc.perform(get("/api/opportunities").param("keyword", "청년 월세"))
                .andExpect(status().isOk());
    }
}
