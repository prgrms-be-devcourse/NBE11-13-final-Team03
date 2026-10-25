# 수집 완료 알림 — 1단계: 완료 메시지 보존

## 범위

정기 수집 종료와 같은 트랜잭션에서 data-service 소유 `collection_outbox_events`에
`COLLECTION_COMPLETED` 메시지를 저장한다. 이 단계는 Outbox 기록까지만 구현하며 외부 전송은 하지 않는다.
사용자 알림 Outbox·큐·notification 테이블을 사용하지 않는다.

| 단계 | 구현 범위 | 상태 |
| --- | --- | --- |
| 1 | 메시지 계약·SQL·완료 판정·Outbox 저장 | 이 PR |
| 2 | 관리자 큐 SQS Publisher·lease·재시도·전용 스케줄러·기본 비활성 설정 | 후속 PR |
| 수신·운영 | notification-service 관리자 큐 Consumer·소비 중복 제거·Slack, 별도 큐·DLQ·IAM | 알림·배포 담당 후속 |

## 완료 기준과 계약

- `SCHEDULED` 작업이 `SUCCESS`, `PARTIAL_SUCCESS`, `FAILED`로 전이될 때 기록한다.
- `MANUAL`, `PENDING`, `RUNNING`, `INTERRUPTED`, 기동 중단 복구는 기록하지 않는다.
- 이미 종료된 작업에 대한 늦은 실패 콜백도 새 메시지를 만들지 않는다.
- 수집일은 `started_at`의 Asia/Seoul 날짜다. 자정을 넘어 종료돼도 시작일에 속한다.
- 출처별 활성 작업은 기존 출처 잠금으로 하나만 허용된다. 고용24의 NCS·전 페이지도 한 작업 안에서 끝난다.
- 집계는 종료한 정기 작업 하나의 값이며 같은 날 수동 작업의 값은 합산하지 않는다.
- `COLLECTION_COMPLETED:{source}:{date}` 유일 제약과 `ON CONFLICT DO NOTHING`으로 출처·수집일당 한 건만 보존한다.
  같은 날 나중에 끝난 정기 작업도 정상 종료하지만 최초 메시지를 교체하거나 추가하지 않는다.

계약 표본은 `contracts/collection-completed-v1.json`이다.
envelope는 schemaVersion(1), messageId(UUID), eventType, correlationId(UUID), occurredAt(종료 시각 UTC), payload다.
messageId와 전체 envelope를 DB에 저장하므로 후속 Publisher도 재전송 시 그대로 사용해야 한다.

본문은 아래 **6개 필드만** 가진다. 사용자·정책 ID, 상태, 실패 사유, 상세 링크는 넣지 않는다.

| 필드 | 값 |
| --- | --- |
| collectedDate | 시작 시각의 한국 날짜 |
| dataSource | YOUTH_POLICY 또는 EMPLOYMENT24 |
| newCount | 새 스냅샷 수 |
| updatedCount | 변경으로 생성된 스냅샷 수 |
| unchangedCount | 기존 해시와 같아 관측 시각만 갱신한 수 |
| failedCount | 수신한 레코드의 저장·유효성 실패 수 |

페이지 조회 실패나 미완료 NCS는 레코드 수로 환산하지 않는다.
따라서 failedCount=0만으로 전체 수집 성공을 판단할 수 없다.
작업 상태·실패 상세는 기존 관리자 API `/api/admin/collection/syncs?date=yyyy-MM-dd`로 확인한다.

## 트랜잭션과 소유권

`SyncJobService.complete/fail`에서 잠근 작업을 종료하고 `CollectionCompletionOutbox`를 호출한다.
기록기는 `MANDATORY` 전파를 사용하므로 독립 트랜잭션을 만들지 않는다.
Outbox 삽입 전에 작업 변경을 flush하고, 중복이면 예외 대신 삽입 건수 0을 반환한다.
저장 실패는 숨기지 않고 작업 종료와 함께 롤백한다. 뒤에서 호출자가 롤백해도 둘 다 남지 않는다.
삽입 로그는 시도 결과이며 최종 커밋이나 SQS 수락을 의미하지 않는다.

`application.collection_outbox_events`는 data-service가 소유한다.
backend `outbox_events`에 쓰지 않으며 사용자 ID 열·다른 서비스 테이블로의 FK가 없다.
sync_job_id는 추적용 숫자이며 FK가 없어 작업 이력 정리 후에도 메시지가 남는다.
모든 새 행은 `PENDING`, retry_count=0이고 전송 상태 변경은 다음 단계 소유다.

## 배포 순서

1. DB 계정·application 스키마를 먼저 준비한다(`database-boundaries.sql`).
2. **새 data-service 기동 전에** application_user로 아래 SQL을 한 번 적용한다.
   새 DB도 이 순서를 따른다. Hibernate update가 먼저 테이블을 만들면 CHECK·기본값이 빠지고 SQL이 실패한다.
3. 이 버전 data-service를 배포한다. 정기 수집 종료 후 Outbox PENDING을 확인한다.

```sh
psql -v ON_ERROR_STOP=1 -f database/scripts/collection-outbox.sql
```

연결 정보는 안전한 인증 설정으로 주입한다. 앱이 SQL을 자동 실행하지 않는다.
이 PR에는 SQS SDK·Publisher·발행 활성화 환경변수가 없으며 큐·IAM·Slack도 변경하지 않는다.
현재 사용자 Consumer는 이 계약을 처리할 수 없으므로 사용자 큐로 메시지를 보내면 안 된다.
후속 단계는 별도의 관리자 큐·DLQ를 사용하고 Publisher는 기본 비활성으로 둔다.
활성화 전에 과거 PENDING을 모두 발행할지 운영에서 정리할지 결정해야 한다.
롤백 시 테이블·기존 메시지는 유지한다.

## 검증

- 단위 테스트: 대상/비대상 상태, 수동 수집 제외, 자정 경계, UTC 시각, 6필드와 표본 구조·카운트 일치.
- PostgreSQL Testcontainers: 실제 SQL 선적용 후 application_user로 서비스 실행,
  출처·날짜 유일성, 동시 무시 삽입, 양쪽 롤백, 기록 실패 시 종료 롤백, 중단 복구·늦은 콜백 제외.
- data-service CI는 새 PostgreSQL 테스트의 실행 > 0, 실패·오류·건너뜀 = 0을 검사한다.
- SQS·Slack E2E와 실제 운영 검증은 후속 단계다.
