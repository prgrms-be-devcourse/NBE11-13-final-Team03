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
 * 수집·정규화 서비스 분리 대비 경계 검사 (docs/ingest-split.md, AGENTS.md Development Rules).
 *
 * 메인 서버에 남는 패키지와 공용 패키지는 수집·정규화 쪽 패키지를 import하지 않는다.
 * 두 쪽은 공유 DB(공고·조건·조건 묶음 테이블)로만 연결되고, 코드 의존은 공용 패키지(opportunity.domain 등)로만 한다.
 * 공용 opportunity.dto에는 조회용 행·참조 DTO만 두고, 정규화 입출력 DTO는 opportunity.normalization.dto에 둔다.
 *
 * 소스 파일을 상대 경로(src/main/java/...)로 읽으므로 backend 디렉터리를 작업 디렉터리로 실행한다
 * (Gradle 기본값. IDE에서 저장소 루트로 실행하면 scansExistingSources가 실패한다).
 */
class IngestBoundaryTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java/com/team3/backend");

    /** 수집·정규화 서비스로 옮길 패키지 */
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

    @Test
    void scansExistingSources() {
        assertThat(MAIN_SOURCES.resolve("eligibility")).isDirectory();
        assertThat(MAIN_SOURCES.resolve("opportunity/domain")).isDirectory();
    }
}
