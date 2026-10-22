package com.team3.backend.opportunity.normalization;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * NCS 코드 → 분류 이름 (ncs-codes.tsv). 직업훈련의 검색·추천 분류에 쓴다.
 * - 1차분류(대분류): 코드 앞 2자리
 * - 2차분류(중분류): 코드 앞 4자리
 * 고용24 원본에는 NCS 코드(ncsCd, 8자리 또는 6자리)만 있고 이름은 없다. 표에 없는 코드는 빈 값을 돌려준다.
 * 스프링 빈이 아니라 정적 표로 둔다 (정규화 서비스 생성자와 테스트 구성을 바꾸지 않으려고).
 */
public final class NcsClassification {

    static final String RESOURCE = "ncs-codes.tsv";
    private static final Map<String, String> NAMES = load();

    private NcsClassification() {
    }

    /** 1차분류(대분류) 이름 */
    public static Optional<String> majorName(String ncsCode) {
        return name(ncsCode, 2);
    }

    /** 2차분류(중분류) 이름 */
    public static Optional<String> middleName(String ncsCode) {
        return name(ncsCode, 4);
    }

    private static Optional<String> name(String ncsCode, int length) {
        if (ncsCode == null) {
            return Optional.empty();
        }
        String code = ncsCode.trim();
        if (code.length() < length) {
            return Optional.empty();
        }
        return Optional.ofNullable(NAMES.get(code.substring(0, length)));
    }

    static Map<String, String> load() {
        InputStream in = NcsClassification.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException(RESOURCE + " 파일이 없습니다");
        }
        Map<String, String> names = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", 2);
                if (parts.length != 2 || parts[1].isBlank()) {
                    throw new IllegalStateException(RESOURCE + " 형식 오류: " + line);
                }
                names.put(parts[0].trim(), parts[1].trim());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.copyOf(names);
    }
}
