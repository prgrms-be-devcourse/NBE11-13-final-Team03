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
3단계 notification-service가 v1/v2를 수신하고 Slack에 최종 결과·소요시간을 표시한다.
Consumer를 먼저 올린 뒤 v2 생산자를 배포하고 별도 큐·DLQ·IAM·Webhook 준비 후 활성화한다.
사용자 Consumer는 이 메시지를 처리할 수 없다.

검증은 10,000건/100건 묶음·중간 장애 복구·마지막 페이지 전 무알림,
수동 전체 재시도와 원본 중복 방지, 실행별 알림·동시 콜백·원자적 롤백·legacy SQL 전환을 포함한다.
CI는 PostgreSQL 통합 테스트의 실행 > 0, 실패·오류·건너뜀 = 0을 검사한다.
