# 수집 완료 관리자 알림 수신·Slack 전달

## 범위

notification-service가 별도 관리자 Standard SQS 큐의 `COLLECTION_COMPLETED`만 처리한다.
수집 서버의 Outbox·Publisher와 같은 `contracts/collection-completed-v2.json`을 계약 표본으로 사용한다.
기존 `collection-completed-v1.json` 메시지도 수신하며 저장된 v1 소비 fingerprint를 유지한다.
관리자 Consumer·연결·전용 스케줄러는 기본 비활성이며 사용자 큐·HTTP 수신·FCM·사용자 API 플래그와 독립적이다.
수집 서버 구현(#75·#76)을 가져오거나 Java 프로젝트 간 의존을 추가하지 않는다.

## 계약과 중복 처리

- envelope는 schemaVersion=1 또는 2, UUID messageId, COLLECTION_COMPLETED, UUID correlationId, UTC occurredAt, payload다.
- v1 payload는 collectedDate, dataSource, newCount, updatedCount, unchangedCount, failedCount 6개만 받는다.
- v2는 기존 집계에 jobId, syncType(SCHEDULED/MANUAL/MANUAL_RETRY), status(SUCCESS/PARTIAL_SUCCESS/FAILED/INTERRUPTED),
  failedPageCount, durationMillis를 추가한 정확한 11필드다. 실행 ID는 양수 long, 페이지 실패는 음수가 아닌 int,
  소요시간은 음수가 아닌 long 밀리초다. SUCCESS에 최종 레코드·페이지 실패가 있으면 거부한다.
- v2 생산자는 자동 재시도까지 끝난 실행당 한 건을 기록한다. 정기·수동 등록·수동 전체 재시도가 포함되며 같은 날 재시도 결과도 별도로 전달한다.
- 소요시간은 생산자 started_at(접수)부터 최종 종료까지로 대기·자동 재시도를 포함하고 큐·Slack 전달 대기는 제외한다.
  v1에는 상태·소요시간을 추정해 넣지 않는다. 두 버전의 필수/추가 필드는 각각 엄격하게 검사한다.
- 출처는 YOUTH_POLICY·EMPLOYMENT24, 날짜는 유효한 yyyy-MM-dd, 카운트는 음수가 아닌 int 정수다.
  필수값 누락·추가 필드·다른 eventType·다른 버전·문자열/소수 카운트는 거부한다. 사용자 메시지 디코더를 타지 않는다.
- `notification.admin_consumed_messages`의 messageId PK로 중복을 제거한다.
  기존 `consumed_messages.user_id NOT NULL`과 사용자 Inbox·Delivery·FCM 데이터는 변경하지 않는다.
- 기존 fingerprint 유틸리티의 필드 정렬·SHA-256을 재사용한다. JSON 필드 순서가 달라도 중복으로 처리한다.
  같은 messageId의 본문이 달라지면 충돌이며 삭제하지 않는다.

## 처리와 장애

1. 기존 NotificationSqsTopology를 별도 관리자 설정·인스턴스로 재사용한다.
   source/DLQ ARN, Standard 여부, redrive 대상·허용 source, visibility, 보존 기간을 확인한 뒤 한 건만 받는다.
2. Handler의 REQUIRES_NEW 트랜잭션에서 관리자 소비 기록을 무시 삽입한다.
   동일 ID의 동시 삽입은 DB 유일 제약에서 기다린다(lock_timeout 5초).
3. 이미 커밋된 동일 본문이면 Slack을 호출하지 않고 중복 처리 결과를 반환한다.
4. 새 메시지는 기록이 미커밋인 동안 Slack을 한 번 호출한다. HTTP 200과 `ok` 응답을 확인한 뒤 소비 기록을 커밋한다.
5. Consumer는 Handler의 커밋된 반환 이후에만 **현재 전달의 receipt handle**로 SQS 메시지를 삭제한다.

Slack 실패는 소비 기록과 함께 롤백하고 SQS에서 삭제하지 않는다. 재전달로 다시 시도하고 장기 실패는 관리자 DLQ로 격리한다.
메시지 검증·동일 ID 충돌·DB 오류도 삭제하지 않는다. 삭제 실패 뒤 재전달되면 커밋된 기록으로 Slack 중복 호출을 막는다.
토폴로지·권한·연결 오류는 수신을 중단하며 사용자 큐를 읽거나 관리자 DLQ를 직접 소비하지 않는다.

**Slack 수락 직후 DB 커밋이 실패하면 같은 정보성 메시지가 다시 전송될 수 있다.** Incoming Webhook에 멱등 키가 없어
외부 전송과 DB 커밋을 한 번에 보장하지 못한다. 계획서가 수용한 중복 범위이며 재현 테스트로 검증한다.
DB 트랜잭션은 이 한 건의 짧은 Slack 호출 동안만 유지한다. HTTP 전체 5초·연결 2초이며 리디렉션·자동 재시도를 하지 않는다.
본문·원격 오류·Webhook URL을 로그나 예외 원인에 보존하지 않고 고정 오류 코드만 남긴다.

Slack 본문은 한국어 여러 줄 요약으로 수집일·데이터 유형·신규·갱신·변경 없음·실패·상태만 표시한다.
YOUTH_POLICY는 "청년 정책", EMPLOYMENT24는 "고용24"로 표시하고 숫자에는 천 단위 구분자와 건 단위를 붙인다.
상태는 SUCCESS=성공, PARTIAL_SUCCESS=부분 성공, FAILED=실패, INTERRUPTED=중단이다.
v1에는 상태가 없으므로 상태 줄을 생략하며 실패 건수로 상태를 추정하지 않는다.
jobId·syncType·failedPageCount·durationMillis는 v2 수신 검증·fingerprint에 그대로 포함하지만 Slack에는 표시하지 않는다.
페이지/NCS 실패는 레코드 수로 환산하지 않는다. 실패 레코드가 0이어도 페이지 실패가 있으면 상태는 부분 성공/실패일 수 있다.

```text
[수집 결과]
수집일: 2026-10-26
데이터 유형: 청년 정책
신규: 8,000건
갱신: 500건
변경 없음: 1,500건
실패: 0건
상태: 성공
```

Webhook 계약 근거: [Slack Incoming Webhooks 공식 문서](https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks).

## 설정

접두사는 `yon.notification.admin.*`다. 다음 환경변수는 `notification-service/.env.example`에 별도 표기한다.

| 환경변수 | 기본값·의미 |
| --- | --- |
| NOTIFICATION_ADMIN_SQS_CONSUMER_ENABLED | false |
| NOTIFICATION_ADMIN_SQS_REGION | 켰을 때 필수 |
| NOTIFICATION_ADMIN_SQS_QUEUE_URL | 별도 관리자 Standard 큐, 필수 |
| NOTIFICATION_ADMIN_SQS_DLQ_URL | 관리자 DLQ, 필수 |
| NOTIFICATION_ADMIN_SLACK_WEBHOOK_URL | 켰을 때 필수. 알림 담당이 발급·보관하고 서비스 전용 환경에 주입 |
| NOTIFICATION_ADMIN_SQS_ENDPOINT | 비움. LocalStack은 같은 loopback origin만 허용 |
| NOTIFICATION_ADMIN_SQS_WAIT_TIME_SECONDS | 10 (0~20) |
| NOTIFICATION_ADMIN_SQS_VISIBILITY_TIMEOUT_SECONDS | 60 (최소 60), 실제 큐 속성과 일치해야 함 |
| NOTIFICATION_ADMIN_SQS_POLL_DELAY_MS | 1000, 양수 |

운영 Webhook은 HTTPS hooks.slack.com(또는 Slack Gov)의 /services/... 경로만 허용한다.
이미 수집 담당이 사용하는 Incoming Webhook이 있다면 같은 채널을 사용할지 확인하고 URL을 이 서비스의
NOTIFICATION_ADMIN_SLACK_WEBHOOK_URL에 안전하게 주입한다. 다른 서비스의 환경변수 이름을 자동으로 참조하지 않는다.
같은 수집 결과를 data-service와 notification-service가 각각 Slack으로 전송하지 않도록 발송 책임은 notification-service에 둔다.
테스트는 명시적인 HTTP loopback Slack stub을 쓴다. URL에 자격정보·query·fragment를 넣거나 리디렉션으로 다른 호스트에 전송하지 않는다.
사용자 source·DLQ와 관리자 source·DLQ의 URL이 겹치면 시작을 거부한다.
두 Consumer가 켜져도 AWS 클라이언트·자격증명·토폴로지·스케줄러를 별도로 선택하며 기존 기본 후보를 대체하지 않는다.

## SQL·배포 준비

1. 기존 `database-boundaries.sql`로 계정·스키마를 준비한다.
2. `notification_user`로 아래 SQL을 한 번 적용한다. 앱이 자동 실행하지 않는다.
   새 로컬 볼륨의 `database/local/03-apply-notification-ddl.sh`에는 이 파일을 포함했다. 기존 볼륨은 수동 적용한다.
3. 기존 SQS CloudFormation 템플릿으로 **별도 관리자 source/DLQ**를 만든다. 코드가 큐나 정책을 변경하지 않는다.
4. 알림 서버 Role에는 관리자 source의 ReceiveMessage·DeleteMessage·GetQueueAttributes와 관리자 DLQ의 GetQueueAttributes만 부여한다.
   데이터 서버 Role에는 관리자 source의 SendMessage만 부여한다. 사용자 큐 권한과 분리한다.
5. 알림 담당의 Slack Webhook을 안전하게 주입하고 관리자 Consumer만 먼저 검증한다.
6. v1/v2 호환 Consumer를 먼저 배포하고 v2 생산자(#75·#76)를 배포한다. 새 관리자 수신 SQL은 이전과 같다.
7. 수집 서버 Publisher를 켜기 전에 과거 PENDING 처리 방침을 정한다. 이후 실제 수집 결과와 관리자 DLQ를 확인한다.

```sh
psql -v ON_ERROR_STOP=1 -f database/scripts/notification-admin.sql
```

관리자 소비 기록은 JDBC로만 접근하며 JPA 사용자 테이블과 분리한다. 활성화 전 이 SQL 적용이 필수다.
SQL 롤백 시에도 기존 사용자 테이블을 변경하지 않는다. 기능을 끄는 롤백은 소비 플래그를 false로 하며 소비 기록을 지우지 않는다.
현재 운영 main compose/deploy.yml의 notification-service 등록·서버 구성은 별도 배포 작업이다.
이 PR은 실제 AWS 큐·IAM·Webhook 발급·운영 환경변수·운영 전송을 변경하지 않는다.

## 검증

2026-10-27 한국어 요약 변경 후 `./gradlew test --rerun-tasks --tests 'com.team3.notification.admin.*'` 실행:
5개 클래스 29개 통과, 건너뜀·실패·오류 0. PostgreSQL·LocalStack·HTTP stub을 사용했으며 실제 AWS/Slack 전송은 미실행이다.

- v1/v2 계약·정확한 필드·숫자 타입·최종 상태·소요시간·기존 fingerprint 호환과 민감 본문 비노출 단위 테스트.
- 기본 비활성·단독 활성화·두 Consumer 동시 활성화·큐 중복·설정 검증.
- 실제 로컬 HTTP stub에 v1 6항목·v2 7항목 한국어 요약 전송, 두 출처·4종 상태 번역과 숨긴 필드 제외,
  HTTP 오류·잘못된 응답·리디렉션 거부.
- PostgreSQL 제한 계정·production DDL + LocalStack + HTTP stub 통합: 커밋 후 삭제,
  중복·동시 중복·본문 충돌, Slack 실패·삭제 실패·DB 커밋 실패 복구, 관리자 DLQ·사용자 큐 분리, 잘못된 토폴로지.
  같은 날 실패·성공 재시도의 각각 1회 전달 및 이전 버전 소비 기록의 중복 제거도 검증한다.
- Notification CI는 관리자 통합 테스트가 스킵 없이 실행됐는지 필수로 확인한다.
- 실제 data-service 프로세스부터 실제 Slack까지의 운영 E2E는 큐·IAM·Webhook·배포 준비 후 수행한다.
