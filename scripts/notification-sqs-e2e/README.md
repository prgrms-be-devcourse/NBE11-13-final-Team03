# 서비스 E2E 자동 검증

Docker Linux 엔진, Java 25, Python 3.11 이상이 필요하다. 저장소 루트에서 실행한다.

```sh
python scripts/notification-sqs-e2e/run.py
```

세 Boot JAR를 빌드하고 일회용 Compose 프로젝트를 만든다. 기존 DB bootstrap·큐 속성과 seed/replay SQL을 재사용한다.
Windows 체크아웃의 CRLF 셸 스크립트는 실행 증거 디렉터리의 복사본만 LF로 바꾼다.
DB·SQS는 호스트에 공개하지 않고, backend와 응답 제어 포트만 loopback의 임의 포트로 공개한다.
개인 `.env`·AWS 인증·운영 DB·운영 발송 대상은 사용하지 않는다.

자동 검증하는 7개 흐름:

1. 기동 시 수집 미실행, 비로그인·일반 사용자·CSRF 누락 요청 차단.
2. 실제 관리자 HTTP→gRPC 수집. 100건 저장 후 마지막 1건을 대기시키고 완료 Outbox/Slack이 없음을 확인. 페이지 403 재시도 후 최종 성공 1건만 전달.
3. 별도의 정규화 요청·결과 101건 완료. 수집 알림과의 완료 순서는 강제하지 않음.
4. 재시도 소진 후 FAILED, Slack 일시 오류 시 소비 기록 rollback·메시지 유지, 복구 후 전달. 수동 전체 재수집 성공은 별도 job/message로 전달.
5. 완료된 관리자 메시지 재전달 시 실제 duplicate 소비·ack 확인, Slack 추가 호출과 사용자 인앱 기록 없음.
6. 기존 사용자 Outbox seed/replay, 실제 사용자 알림 조회·타 사용자 접근 거부·읽음 처리, 중복 기록 방지.
7. 잘못된 계약 메시지는 성공 기록/Slack 없이 소스 큐에서 보존·재수신 후 DLQ로 이동.

앱은 서로 다른 네트워크 공간을 사용한다. `backend`, `data`, `notification` DNS alias로 통신한다.
기존 SQS loopback override와 Slack loopback 시험 URL 검증을 유지하려고 각 앱 네트워크에 SQS 연결 relay를 둔다.
알림 앱과 로컬 Slack stub은 같은 네트워크 공간을 사용한다. 인증 우회용 controller나 운영 설정 변경은 추가하지 않는다.
실제 서명·CSRF 필터를 통과하는 시험용 JWT를 사용하며 OAuth 로그인/FCM 자체를 시험했다고 주장하지 않는다.

완료 조건을 제한 시간 내 polling하고 실패 시 즉시 다음 시나리오를 중단한다. 임의의 고정 sleep으로 완료를 추정하지 않는다.
실제 visibility 60초·redrive 2회로 Slack 재전달과 DLQ를 기다리므로 이 두 구간에는 몇 분이 걸릴 수 있다.
로컬 HTTP 모사는 실제 AWS TLS·IAM·SG나 Slack exactly-once를 보장하지 않는다.

`.validation/service-e2e-<UTC>/`에 결과 JSON·명령/앱 로그·DB/큐/stub 상태를 남긴다.
성공/실패 모두 해당 실행이 생성한 고유 프로젝트만 `down --volumes`로 정리한다. 기존 프로젝트나 볼륨은 건드리지 않는다.
로컬 수정 반복에서만 `--skip-build`로 이미 빌드한 JAR를 재사용할 수 있다. CI는 항상 빌드하고 7개 이름의 실제 통과와 증거 업로드를 요구한다.

## 기존 수동 사용자 Outbox 점검

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
2026-10-25 로컬 검증: 두 Boot JAR 기동, Outbox PUBLISHED, 소비 기록 CREATED 1건·알림 1건 및 큐 0/0 확인.
동일 메시지 재발행 후 published_at 갱신, 저장 건수 1/1·큐 0/0 유지 및 Notification 로그 duplicateMessage=true 확인.
실제 AWS/IAM/운영 큐나 FCM 발송 검증과는 구분한다.
