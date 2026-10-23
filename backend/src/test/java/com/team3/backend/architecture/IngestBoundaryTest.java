package com.team3.backend.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수집·정규화 서비스(data-service) 분리 경계 검사 (docs/ingest-split.md, AGENTS.md Development Rules).
 *
 * 수집·정규화 코드는 data-service로 옮겼다. backend에는 판정·검색이 쓰는 읽기 어댑터(공고 엔티티·조회 리포지토리·조회 DTO·
 * 신청 상태 계산)와 수집 관리자 API 전달(collection.controller·exception)만 남는다.
 * 두 서비스는 공유 DB(공고·조건·조건 묶음 테이블)로만 연결되고, 명령은 Feign(client.DataServiceClient)으로 보낸다.
 *
 * 소스 파일을 상대 경로(src/main/java/...)로 읽으므로 backend 디렉터리를 작업 디렉터리로 실행한다
 * (Gradle 기본값. IDE에서 저장소 루트로 실행하면 scansExistingSources가 실패한다).
 */
class IngestBoundaryTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java/com/team3/backend");

    /** data-service로 옮긴 패키지 */
    private static final Pattern INGEST_IMPORT = Pattern.compile(
            "^import (static )?com\\.team3\\.backend\\.(collection|opportunity\\.(ingest|normalization|service|conditionset)|region\\.(client|service))\\.",
            Pattern.MULTILINE);

    /** 메인 서버에 남는 패키지와 공용 패키지 */
    private static final List<String> MAIN_AND_SHARED = List.of(
            "auth", "eligibility", "search", "interest", "preference", "user", "config", "global",
            "opportunity/domain", "opportunity/dto", "region/directory", "region/domain");

    @Test
    void mainAndSharedPackagesDoNotImportIngestPackages() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String pkg : MAIN_AND_SHARED) {
            Path dir = MAIN_SOURCES.resolve(pkg);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    var matcher = INGEST_IMPORT.matcher(Files.readString(file));
                    while (matcher.find()) {
                        violations.add(MAIN_SOURCES.relativize(file) + " → " + matcher.group().trim());
                    }
                }
            }
        }
        assertThat(violations)
                .as("메인·공용 패키지가 수집·정규화 패키지에 의존하면 서비스 분리 때 끊어진다")
                .isEmpty();
    }

    /** data-service로 옮겨 backend에 없어야 하는 패키지 (collection에는 관리자 API 전달 controller·exception만 남는다) */
    private static final List<String> REMOVED = List.of(
            "opportunity/ingest", "opportunity/normalization", "opportunity/service", "opportunity/conditionset",
            "region/client", "region/service", "collection/service", "collection/domain", "collection/client",
            "collection/dto", "collection/scheduler");

    @Test
    void ingestPackagesAreRemovedFromBackend() {
        assertThat(REMOVED.stream().map(MAIN_SOURCES::resolve).filter(Files::exists).toList())
                .as("수집·정규화 코드는 data-service에만 둔다")
                .isEmpty();
    }

    @Test
    void scansExistingSources() {
        assertThat(MAIN_SOURCES.resolve("eligibility")).isDirectory();
        assertThat(MAIN_SOURCES.resolve("opportunity/domain")).isDirectory();
    }
}
