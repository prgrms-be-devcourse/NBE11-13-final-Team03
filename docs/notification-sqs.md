# SQS Standard 전달 — 9단계

## 현재 범위

Application Outbox의 SQS Publisher와 Notification Consumer·DLQ 연결 검증 코드를 추가했다.
테스트 전용 PostgreSQL/LocalStack 통합 테스트와 수동 배포용 Standard 큐·DLQ 템플릿을 추가했다.
이 문서는 운영 활성화나 AWS 리소스 생성 완료를 뜻하지 않는다. 실제 `.env`와 전달·푸시 플래그는 변경하지 않는다.

## Publisher

- 기존 `NOTIFICATION_PUBLISHER_ENABLED=false` 기본값을 유지한다.
- `NOTIFICATION_PUBLISHER_TRANSPORT=http`(기본) 또는 `sqs` 중 하나만 선택한다.
- HTTP는 기존 Feign Client·URL·내부 키를 사용한다. SQS는 AWS SDK for Java 2.x를 사용하며 HTTP 키를 요구하지 않는다.
- Outbox의 커밋·점유·lease CAS·재시도·설정 오류 시 시도 횟수 보존은 기존 Worker/Store를 재사용한다.
- 일반 알림과 제어 메시지(USER_WITHDRAWN, USER_SESSION_ENDED) 모두 같은 JSON/messageId/correlationId를 그대로 전송한다. 재시도 시 식별자를 다시 생성하지 않는다.
- SQS 응답의 messageId와 본문 MD5를 확인한 뒤에만 성공으로 반환한다.
- **SQS 모드의 PUBLISHED는 큐 수락**이다. HTTP 모드의 수신 DB 커밋 영수증과 의미가 다르며 Notification 처리 완료를 뜻하지 않는다.
- SQS 수락 후 응답 유실/Outbox 완료 저장 실패는 재발행될 수 있다. 소비 기록을 통한 중복 처리가 필요하다.
- SDK 자동 재시도는 끄고 기존 Outbox가 재시도한다. API 전체 10초 제한보다 lease가 충분히 길어야 한다.
- 자격증명·권한·큐 부재·KMS 설정 오류는 PENDING으로 되돌리고 현재 배치를 중단한다. 일시 오류는 기존 Backoff, 잘못된 본문은 FAILED로 분류한다.
- SDK 예외 원문·큐 본문·비밀값은 애플리케이션 오류 코드에 포함하지 않는다.

## 설정 (예시만, 활성화하지 않음)

```properties
NOTIFICATION_PUBLISHER_ENABLED=false
NOTIFICATION_PUBLISHER_TRANSPORT=sqs
NOTIFICATION_SQS_REGION=ap-northeast-2
NOTIFICATION_SQS_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/<account-id>/<standard-queue-name>
```

운영 자격증명은 AWS 기본 공급자 체인과 EC2 IAM Role을 사용한다. 저장소나 이미지에 키를 넣지 않는다.
Application에는 해당 큐의 SendMessage 권한만 부여한다. 큐/DLQ 생성·정책 변경 권한은 애플리케이션에 부여하지 않는다.
큐 타입·RedrivePolicy·보존 기간은 Consumer poll 전 속성 검증으로 확인한다. 실제 운영 적용은 별도다.
로컬 에뮬레이터는 `NOTIFICATION_SQS_ENDPOINT=http://127.0.0.1:<port>`와 같은 origin의 큐 URL을 명시한다.
endpoint override는 호스트 실행용 loopback으로 제한하며 운영에서는 비워 둔다.

## 검증 상태

- `SqsNotificationPublisherTest`: 두 메시지 유형·본문 보존·중복 재발행·오류 분류·수락 응답 검사
- `NotificationSqsConfigTest`: 기본 비활성화·단일 Publisher·HTTP 설정 독립·필수 설정·Standard URL 검증
- 기존 `NotificationPublisherConfigTest`, `HttpNotificationPublisherTest`, `OutboxPublicationWorkerTest` 회귀 검증

2026-10-25 로컬 검증:

- Publisher 설정·발송 및 기존 HTTP/Outbox Worker 회귀 테스트 통과
- Consumer/Topology/설정 단위 테스트 통과
- Notification PostgreSQL/LocalStack 통합 테스트 7개 통과
- Application Outbox/SQS 통합 테스트 6개 통과
- 두 프로젝트 전체 테스트와 Boot JAR 빌드 성공
- Notification 전체 테스트의 스킵·실패·오류 0
- Backend 조건부 PostgreSQL 테스트 19개 별도 실행 통과, 스킵 0
- 두 JAR에 검사 대상 개발·테스트용 파일이 포함되지 않음

AWS 실통신·운영 DLQ 이동은 검증하지 않았다. CloudFormation 템플릿은 YAML 문법만 확인했고 배포 검증은 하지 않았다.

## Consumer

`NOTIFICATION_SQS_CONSUMER_ENABLED=false`가 기본값이다. HTTP Receiver·사용자 API·FCM 플래그와 독립이며,
SQS를 사용한다고 HTTP 사용자 API를 제거하거나 FCM을 켜지 않는다. AWS SDK 의존성은 Notification 프로젝트에도 별도로 추가한다.

활성화하려면 위 region/source queue URL에 더해 `NOTIFICATION_SQS_DLQ_URL`이 필요하다.
Consumer 기동 자체는 AWS 호출을 하지 않는다. 첫 poll 및 이후 60초마다 아래 속성을 읽고 검증한 뒤에만 수신한다.

- source와 DLQ가 Standard이며 각각 URL에서 기대한 ARN과 일치
- source RedrivePolicy의 deadLetterTargetArn이 지정 DLQ, maxReceiveCount는 SQS 허용 범위 1~1000
- DLQ RedriveAllowPolicy는 byQueue이며 source ARN을 명시적으로 허용
- source VisibilityTimeout과 Consumer 설정이 일치
- DLQ 보존 기간이 source보다 길고 SQS 최대 보존 기간 이내

속성 조회/검증 실패 시 해당 poll을 멈춘다. 큐 생성·속성 변경·DLQ 자동 재처리는 하지 않는다.
운영 권한은 source ReceiveMessage/DeleteMessage/GetQueueAttributes, DLQ GetQueueAttributes로 제한한다.
큐 생성·redrive를 수행하는 운영자 권한은 별도로 관리한다.

한 poll에서 한 건만 받아 기존 `NotificationMessageHandler.handle(String)`을 호출한다.
일반 알림과 탈퇴 메시지 모두 기존 수신 트랜잭션과 중복 검증을 그대로 사용한다.
Handler가 DB 커밋 뒤 반환한 모든 정상 결과(CREATED/EXPIRED/CHANNEL_DISABLED/WITHDRAWN/중복 등)는 삭제한다.
삭제에는 이번 수신의 receiptHandle만 사용하며 애플리케이션 messageId나 SQS messageId를 사용하지 않는다.
본문 검증/identity 충돌/DB 오류는 삭제하지 않고 RedrivePolicy에 따라 재전달·DLQ 격리된다.
DB 커밋 뒤 삭제 실패도 롤백하지 않으며 재전달 시 기존 소비 기록을 사용한다.

기본 기술 설정은 long poll 10초, visibility 120초, poll 간격 1초다. 이는 운영 확정 재시도·보존 정책이 아니며,
운영자는 측정한 DB 처리 시간과 장애 복구 요구에 맞춰 큐/Consumer 값을 함께 정해야 한다.
SDK API 전체 제한은 30초이며 long poll 최대 20초보다 길다. SDK 자동 재시도는 끄고 큐 재전달을 사용한다.
Visibility는 DB 잠금이 아니고 Standard 중복을 막지 않는다. 자동 heartbeat는 없으며 처리 시간이 이를 초과해도
DB 사용자 잠금·소비 UNIQUE를 통한 중복 처리는 유지한다. 장시간 DB 장애로 정상 메시지도 DLQ에 갈 수 있어 복구·재처리 절차가 필요하다.
특히 탈퇴 제어 메시지의 DLQ 적체는 Tombstone 적용 지연을 뜻하므로 운영 감시·우선 복구 대상이다.

추가 테스트:

- `NotificationSqsConsumerTest`: Handler → 삭제 순서, 처리 실패 미삭제, 삭제 실패 재전달과 새 receiptHandle, 빈 poll
- `NotificationSqsTopologyTest`: Standard/DLQ/권한/보존/visibility 검증과 설정 재확인
- `NotificationSqsConfigTest`: 기본 비활성화, SDK 등록, 필수 설정과 로컬 override 검증

## PostgreSQL + 로컬 큐 통합 검증

`NotificationSqsPostgresTest`는 Docker의 일회용 PostgreSQL과 LocalStack 컨테이너를 사용한다.
LocalStack 이미지는 `localstack/localstack:4.12.0`으로 고정하고 SQS만 실행한다.
실제 AWS 공급자 체인을 사용하지 않고 테스트용 정적 자격증명과 해당 컨테이너 endpoint만 사용한다.
테스트 안의 큐 생성·purge·visibility 단축은 폐기되는 테스트 컨테이너에만 적용하며 운영 Consumer에는 포함하지 않는다.
실제 로컬 DB·`.env`·AWS 큐·Firebase에는 접근하지 않는다.

Notification 로컬 통합 검증 항목:

- 삭제 직전 별도 PostgreSQL 연결에서 Notification·Delivery·소비 기록의 커밋 확인
- 삭제 실패 후 새 receiptHandle 재전달과 중복 저장 방지
- DB 저장 실패 시 원자적 롤백 및 재전달 후 성공
- 잘못된 메시지의 실제 에뮬레이터 RedrivePolicy에 따른 DLQ 이동
- DB 장애로 DLQ에 들어간 정상 메시지의 원본 본문·식별자 유지 수동 재처리
- 역순 탈퇴 제어 메시지와 늦은 알림의 Tombstone 억제
- 기존 HTTP Handler 소비 기록과 SQS 재전달의 멱등성

에뮬레이터 통과를 실제 AWS IAM·네트워크·암호화·보존 만료·CloudFormation 배포 검증으로 간주하지 않는다.
두 독립 프로젝트를 함께 기동한 Publisher→Consumer 전체 흐름은 위 단일 Notification 통합 테스트와 별개의 검증이다.
이를 위한 격리된 실행 구성과 순서는 [두 서버 로컬 E2E 안내](../scripts/notification-sqs-e2e/README.md)에 추가했다.
기존 개발 DB/.env와 별도 포트의 일회용 DB/큐를 사용한다.
2026-10-25 로컬 E2E 검증: 두 독립 Boot JAR를 실행하여 Outbox PUBLISHED → 소비 기록 CREATED 1건 → 인앱 알림 1건 → 큐 Visible/NotVisible 0/0을 확인했다.
동일 messageId 재발행 뒤 published_at 갱신, 저장 건수 1/1 유지, 큐 0/0과 Notification 로그 duplicateMessage=true를 확인했다.
로컬 E2E 성공은 실제 AWS 검증 또는 실제 FCM 발송 성공을 뜻하지 않는다.

`backend`의 `SqsOutboxPostgresTest`도 별도의 일회용 PostgreSQL/LocalStack을 사용한다.
기존 JPA slice 방식과 Outbox Worker/Store를 재사용하며 Notification Java 클래스에는 의존하지 않는다.

- Outbox 커밋 전 미발행·롤백 미발행과 DB 트랜잭션 밖 SDK 호출
- SQS 수락 후 Outbox PUBLISHED 기록 실패 → 오래된 lease 복구 → 동일 messageId/본문 재발행
- SQS 응답 유실 주입 후 동일 식별자 재시도
- 큐 부재 시 PENDING 유지와 재시도 횟수 미소진
- 일반 알림/탈퇴 제어 메시지의 실제 SDK 전달과 correlationId 보존

재발행된 두 큐 메시지의 중복 소비 억제는 위 Notification 수신 테스트에서 별도로 검증한다.

## 큐·DLQ 구성과 운영 절차

수동 검토용 템플릿은 `deploy/sqs/queues.cloudformation.yaml`, 권한·전환·복구 절차는
[SQS 배포 안내](../deploy/sqs/README.md)에 정리했다. 기존 deploy workflow/compose는 수정하지 않는다.
재시도 횟수·보존 기간은 필수 배포 입력으로 두며 임의 운영 기본값을 확정하지 않는다.
실제 AWS 리소스 생성·배포·운영 플래그 활성화는 수행하지 않았다.

## 공식 참고

- [AWS SDK for Java 2.x SQS 예제](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/java_sqs_code_examples.html)
- [SQS DLQ](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html)
- [Standard DLQ 보존 기간](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/setting-up-dead-letter-queue-retention.html)
