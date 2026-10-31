package com.team3.backend.behavior.retention.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 행동 로그 보관 관리자 API의 요청·응답 */
public final class RetentionAdminDtos {

    private RetentionAdminDtos() {
    }

    /** schedule은 표시용 고정 문자열이다. adminExecuteEnabled가 false면 실행 API는 RETENTION_002로 거부된다 */
    public record Policy(int retainedMonths, int futureMonths, String schedule, boolean schedulerEnabled, boolean schedulerDryRun,
                         boolean adminExecuteEnabled) {
    }

    /** estimatedRows는 PostgreSQL 통계 추정치(정확한 COUNT 아님, ANALYZE 전이면 -1 또는 0)이고 totalBytes는 인덱스 포함 크기다 */
    public record DropItem(String name, LocalDateTime from, LocalDateTime to, long estimatedRows, long totalBytes) {
    }

    /** conflict면 기존 범위와 겹쳐 실행 시 만들지 않고 실패로 기록된다 */
    public record CreateItem(String name, LocalDateTime from, LocalDateTime to, boolean conflict) {
    }

    public record IgnoredItem(String name, String bound) {
    }

    /**
     * status: READY 또는 SKIPPED_NOT_PARTITIONED·SKIPPED_ROLLBACK_WINDOW·SKIPPED_CLOCK_MISMATCH.
     * planToken은 READY일 때만 있으며 실행 요청에 그대로 보낸다.
     */
    public record PreviewResponse(String status, Policy policy, LocalDate baseDate, LocalDate keepFrom, List<DropItem> toDrop,
                                  List<CreateItem> toCreate, List<IgnoredItem> ignored, boolean defaultPartitionHasRows,
                                  String planToken) {
    }

    public record ExecuteRequest(@NotBlank @Size(max = 128) String planToken) {
    }

    /** status: COMPLETED 또는 COMPLETED_WITH_FAILURES. failed는 "파티션명: 원인" 목록이다 */
    public record ExecuteResponse(String status, LocalDate keepFrom, List<String> created, List<String> dropped,
                                  List<String> failed, List<String> ignored, LocalDateTime executedAt) {
    }
}
