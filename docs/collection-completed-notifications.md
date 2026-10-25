# 수집 완료 알림 — 메시지 보존과 관리자 큐 발행

## 범위

정기 수집 종료와 같은 트랜잭션에서 data-service 소유 `collection_outbox_events`에
`COLLECTION_COMPLETED` 메시지를 저장한다. Publisher는 기본 비활성이며 명시적으로 켠 경우에만 관리자 큐로 전송한다.
사용자 알림 Outbox·큐·notification 테이블을 사용하지 않는다.

| 단계 | 구현 범위 | 상태 |
| --- | --- | --- |
| 1 | 메시지 계약·SQL·완료 판정·Outbox 저장 | 구현 |
| 2 | 관리자 큐 SQS Publisher·lease·재시도·전용 스케줄러·기본 비활성 설정 | 구현, 기본 비활성 |
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
모든 새 행은 `PENDING`, retry_count=0이며 Publisher 활성화 여부와 무관하게 저장된다.

## SQS Publisher와 복구

backend Outbox의 저장소·Worker·SQS 전송·오류 분류를 data-service 내부로 복제했다.
공용 Java 모듈이나 backend 의존은 추가하지 않는다. AWS SDK v2 BOM·sqs·url-connection-client는 backend와 같은 2.57.2다.

- 전용 `collectionOutboxScheduler` 한 스레드가 5초마다 최대 10건을 처리한다.
  `collectionExecutor`와 별도이며 Boot의 일반 taskScheduler도 대체하지 않는다.
- 짧은 REQUIRES_NEW 트랜잭션에서 `FOR UPDATE SKIP LOCKED`로 한 행을 선점하고 `PROCESSING`·UUID lease를 저장한다.
  실제 SQS 요청은 DB 트랜잭션 밖에서 수행한다. 수집 종료나 DB 잠금을 네트워크 응답까지 붙잡지 않는다.
- SQS 응답의 messageId와 본문 MD5가 유효해야 `PUBLISHED`로 바꾼다. 이는 큐 수락이며 Slack 전달 완료를 뜻하지 않는다.
- 저장된 envelope·messageId는 재전송에도 그대로 쓰며 correlationId를 message attribute에도 담는다.
  `COLLECTION_COMPLETED` 이외의 eventType·행과 다른 messageId·잘못된 correlationId는 영구 오류다.
- 일시 오류는 1·2·4·8분처럼 지수 백오프로 재시도하고 최대 5회 후 FAILED로 남긴다(배율 최대 64).
  SDK 자체 재시도는 끄고 API 전체 10초·단일 시도 5초로 제한한다.
- 자격증명·권한·큐 설정 오류는 PENDING으로 반환하고 시도 횟수를 늘리지 않으며 현재 배치를 중단한다.
- 기동 중단이나 SQS 수락 뒤 DB 저장 실패로 남은 PROCESSING은 1분 lease 만료 후 복구한다.
  lease 소유권 CAS로 오래된 Worker가 새 소유자의 상태를 덮어쓰지 못한다. 마지막 시도의 중단도 FAILED로 정리된다.
- 원격 오류 본문·자격증명은 기록하지 않고 고정 오류 코드만 보존한다.

설정 접두사는 `yon.collection.notification.publisher.*`이며 아래 별도 별칭을 사용한다.
기존 `NOTIFICATION_*` 환경변수로 이 Publisher가 켜지거나 사용자 큐 주소가 재사용되지 않는다.

| 환경변수 | 기본값·요구 |
| --- | --- |
| COLLECTION_NOTIFICATION_PUBLISHER_ENABLED | false |
| COLLECTION_NOTIFICATION_SQS_REGION | 켰을 때 필수 |
| COLLECTION_NOTIFICATION_SQS_QUEUE_URL | 켰을 때 필수, 별도 관리자 Standard 큐 |
| COLLECTION_NOTIFICATION_SQS_ENDPOINT | 비움. LocalStack 사용 시 같은 loopback origin만 허용 |
| COLLECTION_NOTIFICATION_PUBLISHER_POLL_DELAY_MS | 5000, 양수 |

batch-size(10), max-attempts(5), lease-timeout(PT1M), retry-delay(PT1M)는 속성 접두사 아래에서 조정할 수 있다.
lease는 SDK 10초 호출과 복구 여유를 포함해 15초보다 길어야 한다.
운영 큐 URL은 해당 region의 HTTPS SQS 호스트여야 하며 FIFO·인증정보·query·fragment URL은 거부한다.

## 배포 순서

1. DB 계정·application 스키마를 먼저 준비한다(`database-boundaries.sql`).
2. **새 data-service 기동 전에** application_user로 아래 SQL을 한 번 적용한다.
   새 DB도 이 순서를 따른다. Hibernate update가 먼저 테이블을 만들면 CHECK·기본값이 빠지고 SQL이 실패한다.
3. 이 버전 data-service를 배포한다. 정기 수집 종료 후 Outbox PENDING을 확인한다.

```sh
psql -v ON_ERROR_STOP=1 -f database/scripts/collection-outbox.sql
```

연결 정보는 안전한 인증 설정으로 주입한다. 앱이 SQL을 자동 실행하지 않는다.
실제 큐·IAM·Slack은 이 코드로 생성하거나 변경하지 않는다.
현재 사용자 Consumer는 이 계약을 처리할 수 없으므로 사용자 큐로 메시지를 보내면 안 된다.
관리자 큐·DLQ는 `deploy/sqs/queues.cloudformation.yaml`을 별도 QueueName/스택으로 배포한다.
데이터 서버 Instance Role에는 그 관리자 큐 ARN의 `sqs:SendMessage`만 부여한다. 사용자 큐·DLQ 접근·큐 생성 권한은 주지 않는다.
SQS HTTPS 아웃바운드 443을 허용하고 자격증명은 SDK 기본 공급자 체인/Instance Role로 주입한다.
알림 담당의 관리자 Consumer·중복 소비 제거·Slack을 준비하고 검증한 후 Publisher를 활성화한다.
활성화 전에 과거 PENDING을 모두 발행할지 운영에서 정리할지 결정해야 한다.
발행 플래그를 false로 바꾸면 발행만 멈추고 수집·Outbox 저장은 계속된다. 테이블·기존 메시지는 유지한다.

## 검증

- 단위 테스트: 대상/비대상 상태, 수동 수집 제외, 자정 경계, UTC 시각, 6필드와 표본 구조·카운트 일치.
- PostgreSQL Testcontainers: 실제 SQL 선적용 후 application_user로 서비스 실행,
  출처·날짜 유일성, 동시 무시 삽입, 양쪽 롤백, 기록 실패 시 종료 롤백, 중단 복구·늦은 콜백 제외.
- data-service CI는 새 PostgreSQL 테스트의 실행 > 0, 실패·오류·건너뜀 = 0을 검사한다.
- SDK 단위 테스트: 본문·ID 불변, 잘못된 메시지/MD5, 자격증명·권한·throttle·timeout 분류와 민감 본문 비노출.
- LocalStack+PostgreSQL: 실제 SDK 전송, 커밋 전 미발행, 응답 유실·DB ACK 실패 시 동일 본문 재발행,
  잘못된 큐 지연, 지수 백오프·최대 횟수, lease 복구·CAS·동시 선점. CI는 실행 > 0·실패/오류/스킵 = 0을 검사한다.
- 실제 관리자 Consumer·Slack E2E와 운영 검증은 후속 단계다.
