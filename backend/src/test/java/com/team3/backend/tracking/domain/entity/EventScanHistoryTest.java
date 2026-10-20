package com.team3.backend.tracking.domain.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventScanHistoryTest {

    private static final LocalDate SCAN_DATE =
            LocalDate.of(2026, 10, 20);

    private static final Instant STARTED_AT =
            Instant.parse("2026-10-20T02:00:00Z");

    @Test
    void 새_스캔은_RUNNING_상태로_시작한다() {
        EventScanHistory history =
                EventScanHistory.start(
                        SCAN_DATE,
                        STARTED_AT
                );

        assertThat(history.getScanDate()).isEqualTo(SCAN_DATE);
        assertThat(history.getStatus())
                .isEqualTo(EventScanStatus.RUNNING);
        assertThat(history.getStartedAt())
                .isEqualTo(STARTED_AT);
        assertThat(history.getRetryCount()).isZero();
        assertThat(history.getCreatedAt())
                .isEqualTo(STARTED_AT);
        assertThat(history.getUpdatedAt())
                .isEqualTo(STARTED_AT);
        assertThat(history.getExecutionId()).isNotBlank();
    }

    @Test
    void 실행_중인_스캔을_성공으로_완료한다() {
        EventScanHistory history =
                EventScanHistory.start(
                        SCAN_DATE,
                        STARTED_AT
                );

        Instant completedAt =
                STARTED_AT.plusSeconds(10);

        history.succeed(completedAt);

        assertThat(history.getStatus())
                .isEqualTo(EventScanStatus.SUCCESS);
        assertThat(history.getCompletedAt())
                .isEqualTo(completedAt);
        assertThat(history.getLastError()).isNull();
    }

    @Test
    void 실행_중인_스캔을_실패로_완료한다() {
        EventScanHistory history =
                EventScanHistory.start(
                        SCAN_DATE,
                        STARTED_AT
                );

        Instant failedAt =
                STARTED_AT.plusSeconds(10);

        history.fail(
                failedAt,
                "정책 조회 실패"
        );

        assertThat(history.getStatus())
                .isEqualTo(EventScanStatus.FAILED);
        assertThat(history.getCompletedAt())
                .isEqualTo(failedAt);
        assertThat(history.getLastError())
                .isEqualTo("정책 조회 실패");
    }

    @Test
    void 실패한_스캔을_재시작하면_재시도_횟수가_증가한다() {
        EventScanHistory history =
                EventScanHistory.start(
                        SCAN_DATE,
                        STARTED_AT
                );

        history.fail(
                STARTED_AT.plusSeconds(10),
                "일시적 오류"
        );

        Instant restartedAt =
                STARTED_AT.plusSeconds(20);

        history.restart(restartedAt);

        assertThat(history.getStatus())
                .isEqualTo(EventScanStatus.RUNNING);
        assertThat(history.getStartedAt())
                .isEqualTo(restartedAt);
        assertThat(history.getCompletedAt()).isNull();
        assertThat(history.getRetryCount()).isEqualTo(1);
        assertThat(history.getLastError()).isNull();
        String previousExecutionId =
                history.getExecutionId();

        history.restart(restartedAt);

        assertThat(history.getExecutionId())
                .isNotEqualTo(previousExecutionId);
    }

    @Test
    void 성공한_스캔은_다시_시작할_수_없다() {
        EventScanHistory history =
                EventScanHistory.start(
                        SCAN_DATE,
                        STARTED_AT
                );

        history.succeed(
                STARTED_AT.plusSeconds(10)
        );

        assertThatThrownBy(() ->
                history.restart(
                        STARTED_AT.plusSeconds(20)
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "성공한 날짜 스캔은 다시 시작할 수 없습니다."
                );
    }

    @Test
    void 완료된_스캔의_상태를_다시_변경할_수_없다() {
        EventScanHistory history =
                EventScanHistory.start(
                        SCAN_DATE,
                        STARTED_AT
                );

        history.fail(
                STARTED_AT.plusSeconds(10),
                "실패"
        );

        assertThatThrownBy(() ->
                history.succeed(
                        STARTED_AT.plusSeconds(20)
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "실행 중인 날짜 스캔만 상태를 변경할 수 있습니다."
                );
    }
}