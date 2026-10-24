# 독립 JAR 두 서버 + 로컬 SQS 전체 흐름

9단계 검증용이며 기존 docker/compose.yaml 및 deploy 구성과 독립적이다.
실제 AWS·Firebase·기존 yon/yon_boundary DB와 개인 .env를 사용하지 않는다.
가짜 비밀번호·AWS 키는 이 일회용 구성 전용이다. 운영에 복사하지 않는다.

범위: 커밋된 Application Outbox → SQS → Notification Handler → 인앱 알림·소비 기록 → 큐 삭제.
날짜 감지·fan-out·로그인·실제 FCM은 별도 검증이다.
Java 25, Docker Compose, 빌드된 두 Boot JAR가 필요하다. 저장소 루트에서 실행한다.

## 1. 격리된 DB와 로컬 큐

```sh
docker compose -f scripts/notification-sqs-e2e/compose.yaml -p yon-sqs-e2e up -d --wait
sh scripts/notification-sqs-e2e/setup-queues.sh
```

DB/SQS 포트는 15432/14566이다. 두 서버는 기존 개발 포트와 다른 18080/18081을 사용한다.

## 2. 서로 다른 터미널에서 서버 실행

```sh
sh scripts/notification-sqs-e2e/run-service.sh notification
```

```sh
sh scripts/notification-sqs-e2e/run-service.sh backend
```

두 서버의 Started 로그 및 SQS 설정 오류가 없는지 확인한다. 평소 .env는 바꾸지 않는다.
검증 프로세스에만 Publisher/Consumer를 켜고 HTTP Receiver/Facade/사용자 API/FCM은 끈다.

## 3. Outbox fixture 커밋 및 확인

```sh
docker compose -f scripts/notification-sqs-e2e/compose.yaml -p yon-sqs-e2e exec -T db \
  env PGPASSWORD=e2e-application-only psql -h 127.0.0.1 -U application_user -d yon_sqs_e2e \
  -v ON_ERROR_STOP=1 < scripts/notification-sqs-e2e/seed.sql
sh scripts/notification-sqs-e2e/verify.sh
```

Worker/long poll 때문에 잠시 뒤 verify를 다시 실행해야 할 수 있다.
Outbox PUBLISHED, consumed_messages CREATED 1건, notifications 1건, source Visible/NotVisible 0을 확인한다.
실재 사용자를 만들지 않고 scalar user/policy ID로 메시지 계약을 검증한다.
인앱만 요청하며 설정 부재 시 인앱 기본 허용을 사용한다. FCM은 CLI 설정으로 끈다.

## 4. 동일 메시지 재발행

```sh
docker compose -f scripts/notification-sqs-e2e/compose.yaml -p yon-sqs-e2e exec -T db \
  env PGPASSWORD=e2e-application-only psql -h 127.0.0.1 -U application_user -d yon_sqs_e2e \
  -v ON_ERROR_STOP=1 < scripts/notification-sqs-e2e/replay.sql
sh scripts/notification-sqs-e2e/verify.sh
```

검증 메시지 한 건만 재발행하며 본문/messageId/sourceEventId/correlationId는 유지한다.
UPDATE 1, 새로운 published_at, Notification 로그 duplicateMessage=true, 저장 건수 1/1 유지 및 큐 0/0을 확인한다.
verify는 duplicateMessage 로그를 자동 검사하지 않으므로 로그도 직접 확인해야 재전달 검증 완료다.
운영 Outbox를 이 SQL로 일괄 재발행하지 않는다.

## 5. 종료

두 Java 프로세스 터미널에서 Ctrl+C 후 E2E 프로젝트만 종료한다.

```sh
docker compose -f scripts/notification-sqs-e2e/compose.yaml -p yon-sqs-e2e down
```

DB는 tmpfs, SQS는 메모리 저장이므로 종료 시 검증 데이터가 사라진다. 재실행 시 초기화부터 진행한다.
기존 개발 DB 볼륨이나 AWS 큐는 삭제하지 않는다.
2026-10-25 사용자 실행 결과: 두 Boot JAR 기동, Outbox PUBLISHED, 소비 기록 CREATED 1건·알림 1건 및 큐 0/0 확인.
동일 메시지 재발행 후 published_at 갱신, 저장 건수 1/1·큐 0/0 유지 및 Notification 로그 duplicateMessage=true 확인.
실제 AWS/IAM/운영 큐나 FCM 발송 검증과는 구분한다.
