package com.team3.backend.eligibility.benchmark;

import com.team3.backend.eligibility.service.EligibilityResultService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 벤치마크 결과 파일에 남길 측정 환경 사양. 다른 기계의 결과를 섞어 비교하지 않도록 기계·DB·저장 설정을 함께 기록한다.
 * 환경 이름은 YON_BENCH_ENV_LABEL로 지정한다 (예: "홈서버 i9-9880H", "EC2 t3.small").
 */
final class BenchmarkEnvironment {

    private BenchmarkEnvironment() {
    }

    static Map<String, String> describe(JdbcTemplate jdbc, EligibilityResultService resultService) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("환경 이름 (YON_BENCH_ENV_LABEL)", System.getenv().getOrDefault("YON_BENCH_ENV_LABEL", "(지정 안 함)"));
        m.put("호스트 이름", hostName());
        m.put("CPU 모델", cpuModel());
        m.put("전체 메모리", totalMemory());
        m.put("PostgreSQL 설정", "shared_buffers=" + show(jdbc, "shared_buffers")
                + ", work_mem=" + show(jdbc, "work_mem")
                + ", synchronous_commit=" + show(jdbc, "synchronous_commit"));
        m.put("refreshAll 페이지 크기 (CHUNK)", staticInt("CHUNK"));
        m.put("저장 묶음 크기 (yon.eligibility.result-write-batch-size)", batchSize(resultService));
        return m;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "알 수 없음";
        }
    }

    private static String cpuModel() {
        try {
            return Files.readAllLines(Path.of("/proc/cpuinfo")).stream()
                    .filter(l -> l.startsWith("model name"))
                    .map(l -> l.substring(l.indexOf(':') + 1).trim())
                    .findFirst().orElse("알 수 없음");
        } catch (Exception e) {
            return "알 수 없음 (" + System.getProperty("os.name") + ")";
        }
    }

    private static String totalMemory() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return String.format("%.1f GB", os.getTotalMemorySize() / 1024.0 / 1024 / 1024);
        }
        return "알 수 없음";
    }

    private static String show(JdbcTemplate jdbc, String name) {
        try {
            return jdbc.queryForObject("SHOW " + name, String.class);
        } catch (Exception e) {
            return "?";
        }
    }

    private static String staticInt(String field) {
        try {
            Field f = EligibilityResultService.class.getDeclaredField(field);
            f.setAccessible(true);
            return String.valueOf(f.getInt(null));
        } catch (ReflectiveOperationException e) {
            return "알 수 없음";
        }
    }

    private static String batchSize(EligibilityResultService resultService) {
        try {
            Field f = EligibilityResultService.class.getDeclaredField("writeBatchSize");
            f.setAccessible(true);
            return String.valueOf(f.getInt(AopTestUtils.getTargetObject(resultService)));
        } catch (ReflectiveOperationException e) {
            return "없음 (일괄 저장 이전 코드)";
        }
    }
}
