# 수집 실행 완료 알림

## 실행과 최종 결과

정기 실행(SCHEDULED)과 수동 등록(MANUAL)은 같은 Worker를 사용한다.
페이지를 받은 뒤 최대 100건씩 트랜잭션으로 저장하고 다음 페이지를 가져온다.
10,000건이면 정상 경로에서 100개 묶음을 저장한 뒤 **실행 전체의 최종 알림 한 건**을 기록한다.
고용24의 NCS 01~24도 하나의 실행에 포함한다. 정규화 완료를 기다리는 알림은 아니다.

- 두 출처 모두 모든 페이지의 일시 오류를 최초 시도 포함 최대 3회 시도한다. 재시도 전 100ms, 200ms 대기한다.
- 인증·잘못된 요청·잘못된 응답 등 재시도 불가 오류는 반복하지 않는다. 기존 조기 종료·최대 페이지 안전장치는 유지한다.
- 저장 중 일시 DB 오류(TransientDataAccessException)도 새 트랜잭션에서 최대 3회 시도한다.
  묶음이 끝내 실패하면 롤백된 묶음을 건별로 처리한다. 건별 일시 오류도 최대 3회 시도한다.
  유효성·제약 조건 오류는 같은 요청을 반복하지 않는다.
- 재시도 중 회복한 오류는 실패 건수에 누적하지 않는다. 최종 실패한 페이지·항목만 이력에 남긴다.
- 전체 순회와 저장이 끝나면 SUCCESS/PARTIAL_SUCCESS/FAILED를 확정한다. 최종 중단은 INTERRUPTED다.
  작업 종료와 완료 Outbox 삽입은 같은 트랜잭션이다. 실행 중간·묶음별 알림은 만들지 않는다.
- 서버 재시작으로 남은 작업도 INTERRUPTED로 확정하고 결과를 보존한다. 재시작 시 전체 수집 자동 재개는 하지 않는다.

## 수동 재시도

`POST /api/admin/collection/syncs/{jobId}/retry`는 ADMIN 전용이며 gRPC RetrySync를 호출한다.
FAILED/PARTIAL_SUCCESS/INTERRUPTED 이력을 대상으로 **출처 전체를 새 MANUAL_RETRY 실행으로 재수집**한다.
실패 항목만 골라 처리하지 않는다. 기본 pageSize=100/maxPages=1000이며 고용24는 재실행일 기준 기존 6개월 조회 범위를 사용한다.
이전 실행 상태·집계를 보존하고 새 jobId를 반환한다(202 + 새 이력 Location).
성공 원본의 재수집은 기존 fingerprint·출처/외부 ID 정합성 처리를 재사용한다.
활성 실행·비활성 출처, 성공·대기·실행 중 이력의 재시도는 거부한다.
반복 수동 재시도는 각각 별도 실행·최종 알림이며 같은 날짜여도 생략하지 않는다.

## 메시지와 중복 방지

`application.collection_outbox_events`는 data-service 소유이며 사용자 Outbox·큐를 사용하지 않는다.
`COLLECTION_COMPLETED:{jobId}` 및 `sync_job_id UNIQUE`로 **실행당 한 건**을 보장한다.
늦은 종료 콜백은 이미 끝난 작업을 바꾸거나 메시지를 보충하지 않는다.
기록기는 MANDATORY, INSERT는 ON CONFLICT DO NOTHING이다. 기존 messageId·본문은 덮어쓰지 않는다.
같은 날 실패한 실행과 성공한 재실행은 서로 다른 메시지다. SQS 전송 재시도는 저장 본문·ID를 유지한다.

새 생산 계약은 [collection-completed-v2.json](../contracts/collection-completed-v2.json)이다.
envelope는 schemaVersion=2, messageId, eventType=COLLECTION_COMPLETED, correlationId, occurredAt(종료 UTC), payload다.
기존 v1 표본은 이전 메시지 수신 호환성을 위해 보존한다. 기존 메시지에 상태·소요시간을 추정해 덧붙이지 않는다.

| payload 필드 | 의미 |
| --- | --- |
| collectedDate / dataSource | started_at의 KST 날짜 / YOUTH_POLICY 또는 EMPLOYMENT24 |
| newCount / updatedCount / unchangedCount | 새 스냅샷 / 변경 스냅샷 / 유지 건수 |
| failedCount | 최종 레코드 실패 수. 페이지 실패를 레코드 수로 환산하지 않는다 |
| jobId / syncType | 실행 ID / SCHEDULED, MANUAL, MANUAL_RETRY |
| status | SUCCESS, PARTIAL_SUCCESS, FAILED, INTERRUPTED |
| failedPageCount | 재시도까지 소진한 최종 조회 실패 페이지 수 |
| durationMillis | started_at~finished_at 경과 밀리초. 자동 재시도 포함, SQS·Slack 전달 대기 제외 |

기존 started_at은 접수 시각이므로 실행기 대기가 있으면 소요시간에 포함된다.
시스템 시계 역전으로 음수가 되면 0으로 표시한다. occurredAt과 finished_at은 PostgreSQL 마이크로초 정밀도로 맞춘다.
실패 사유·원문·사용자 ID·정책 ID·상세 링크는 알림에 넣지 않는다.
페이지 실패·미완료 순회는 레코드 실패가 0이어도 status로 구분한다.

## 배포와 검증

새 DB는 계정·스키마 준비 후 application_user로 `database/scripts/collection-outbox.sql`을 앱 기동 전에 적용한다.
이전 SQL을 이미 설치한 DB는 `collection-outbox-per-job.sql`을 선적용한다. 기존 메시지는 수정·삭제하지 않는다.
동일 작업의 legacy 날짜 키도 job UNIQUE가 중복을 막는다. SQL은 앱이 자동 실행하지 않는다.
운영 SQL·실제 env·외부 API·AWS·Slack 자원은 이번 변경으로 적용하지 않는다.

1단계는 수집·재시도 API·메시지 보존까지다. 2단계 Publisher는 별도 관리자 SQS 큐에 발행하며 기본 비활성이다.
3단계 notification-service가 v1/v2를 수신하고 Slack에 수집일·유형·4종 집계와 v2의 실행·페이지 실패·소요 시간·최종 결과를
한국어 여러 줄로 표시한다. 표시 형식은 [관리자 알림 안내](collection-admin-notifications.md)를 따른다.
Consumer를 먼저 올린 뒤 v2 생산자를 배포하고 별도 큐·DLQ·IAM·Webhook 준비 후 활성화한다.
사용자 Consumer는 이 메시지를 처리할 수 없다.

검증은 10,000건/100건 묶음·중간 장애 복구·마지막 페이지 전 무알림,
수동 전체 재시도와 원본 중복 방지, 실행별 알림·동시 콜백·원자적 롤백·legacy SQL 전환을 포함한다.
CI는 PostgreSQL 통합 테스트의 실행 > 0, 실패·오류·건너뜀 = 0을 검사한다.

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
