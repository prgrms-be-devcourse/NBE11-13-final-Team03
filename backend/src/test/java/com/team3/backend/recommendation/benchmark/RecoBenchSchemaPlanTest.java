package com.team3.backend.recommendation.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추천 벤치마크 DB에 Hibernate update가 무엇을 바꾸려는지 스크립트로만 만든다 (DB에는 아무것도 실행하지 않는다).
 * 생성된 스크립트를 검토한 뒤 사람이 psql로 적용한다.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=${YON_RECO_BENCH_DB_URL}",
        "spring.datasource.username=application_user",
        "spring.datasource.password=${YON_RECO_BENCH_DB_PASSWORD}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.hikari.connection-init-sql=SET search_path TO application, pg_catalog",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.default_schema=application",
        "spring.jpa.properties.jakarta.persistence.schema-generation.database.action=none",
        "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=update",
        "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=build/reports/reco-bench/schema-update.sql",
        "spring.jpa.properties.hibernate.hbm2ddl.delimiter=;",
        "spring.jpa.properties.hibernate.format_sql=false",
        "app.scheduling.enabled=false",
        "collection.schedule.enabled=false",
        "yon.normalization.condition-set-backfill.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "YON_RECO_BENCH_PHASE", matches = "schema-plan")
class RecoBenchSchemaPlanTest {

    @Test
    void writesUpdateScriptWithoutTouchingDatabase() throws Exception {
        Path script = Path.of("build/reports/reco-bench/schema-update.sql");
        assertThat(script).exists();
        System.out.println("RECO_BENCH_SCHEMA_PLAN " + script.toAbsolutePath() + " lines=" + Files.readAllLines(script).size());
    }
}
