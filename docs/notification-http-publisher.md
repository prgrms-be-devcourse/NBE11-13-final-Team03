# HTTP Publisher와 Notification 수신 연결 — 개정안 6단계

## 범위와 이번에 확인한 계약

2026-10-23 개발 요청에서 다음 내부 연결 계약을 확인했다.

- Application의 Feign Client → `POST /internal/notification-messages`
- `X-INTERNAL-KEY` 헤더에 전용 `NOTIFICATION_INTERNAL_API_KEY` 사용
- 기존 backend 내부 API의 `INTERNAL_API_KEY`와 별도 관리
- 해당 POST만 인증 후 허용하고 다른 Notification 요청은 계속 거부
- Publisher 기본 비활성화, 명시적으로 켠 경우에만 기존 PENDING Outbox 발행

두 서버는 계속 독립 Spring Boot 프로젝트다. 공유 Java 타입이나 프로젝트 의존성은 없다.
강사 예제와 같은 Spring Cloud OpenFeign starter와 BOM 2025.1.3을 사용한다.
BackendApplication의 `@EnableFeignClients`가 `client/NotificationClient`의 `@FeignClient`를 등록한다.
직접 `Feign.builder()`를 호출하거나 feign-core 버전을 별도로 고정하지 않는다.
인터페이스는 `@PostMapping`, `@RequestHeader`, `@RequestBody`로 요청을 선언한다.
기존 Jackson 3·저장된 JSON 계약과 Publisher의 소비 응답 검증을 그대로 사용한다.
서비스 디스커버리·Gateway·로드밸런싱은 이번 범위에 추가하지 않는다.

### 강사 예제와 대응하는 파일 배치

```text
backend/src/main/java/com/team3/backend/
├── BackendApplication.java                  # @EnableFeignClients
├── client/
│   ├── NotificationClient.java              # @FeignClient, @PostMapping
│   └── config/NotificationFeignConfig.java   # 이 클라이언트의 타임아웃·재시도·로그 설정
└── outbox/
    ├── config/NotificationPublisherConfig.java
    ├── publisher/HttpNotificationPublisher.java
    └── service/OutboxPublicationWorker.java
```

참고한 원본은 [강사 BoardClient](https://github.com/DongWoonKim/programmers-dev-lect/blob/master/msa/web-service/src/main/java/com/example/spring/webservice/client/BoardClient.java),
[실행 클래스](https://github.com/DongWoonKim/programmers-dev-lect/blob/master/msa/web-service/src/main/java/com/example/spring/webservice/WebServiceApplication.java),
[Gradle 설정](https://github.com/DongWoonKim/programmers-dev-lect/blob/master/msa/web-service/build.gradle)이다.
버전 호환은 [Spring 공식 호환표](https://spring.io/projects/spring-cloud/)를 확인했다.

NotificationFeignConfig는 이 클라이언트의 자식 컨텍스트에서만 로드한다.
일반 `@Configuration`/`@Component`로 스캔시켜 다른 클라이언트의 기본 설정을 덮어쓰지 않는다.
Outbox는 이미 완성된 JSON을 보관하므로 송신 시 새 Entity나 상대 프로젝트 DTO로 변환하지 않는다.
응답도 Publisher가 원문을 제한된 크기로 읽어 소비 계약을 확인하며 기존 동작을 유지한다.

Publisher 비활성 상태에서도 Spring의 Feign 프록시 정의는 등록될 수 있으나,
발행 Worker·Publisher·전용 발행 스케줄러는 만들지 않으며 HTTP 전송도 수행하지 않는다.

날짜 감지·이벤트 Worker·사용자별 Outbox 생성은 기존 3~4단계 코드를 재사용한다.
수신은 5단계의 동일 NotificationMessageHandler를 사용한다.
수신 설정 API·Endpoint·Delivery·FCM·탈퇴·SQS는 구현하지 않는다.
사용자 설정·탈퇴 차단이 없는 개발 검증 경로이며 운영 알림 발송 완료로 간주하지 않는다.

## 연결 및 상태 전이

1. 커밋되어 있고 nextRetryAt이 도래한 PENDING Outbox를 한 건씩 점유한다.
2. 짧은 별도 트랜잭션에서 PUBLISHING·processingStartedAt·새 leaseToken을 저장한다.
3. DB 트랜잭션 밖에서 사용자별 JSON을 HTTP 전송한다.
4. Notification은 알림·소비 기록을 커밋한 뒤 HTTP 200 처리 결과를 반환한다.
5. Application은 messageId와 알려진 소비 결과를 검증하고, 같은 leaseToken 소유자일 때만 PUBLISHED로 완료한다.

Notification 결과는 CREATED, DUPLICATE_EVENT, EXPIRED, CHANNEL_DISABLED다.
만료·채널 비활성도 정상 소비이므로 Outbox는 PUBLISHED가 된다.
PUBLISHED는 소비 완료 응답을 확인했다는 의미이지 푸시 발송·사용자 읽음을 뜻하지 않는다.
단순 2xx, 202, 빈 응답, 다른 메시지 ID, 알 수 없는 결과는 발행 완료 근거로 사용하지 않는다.

HTTP 연결/읽기 제한은 기본 1초/2초다. 리다이렉트와 Feign 내부 자동 재시도는 끈다.
응답 JSON은 최대 16 KiB까지 읽는다. 전용 단일 스케줄러에서 한 회 최대 10건을 순차 처리하며,
각 건을 전송 직전에 점유해 미리 점유한 배치의 lease가 대기 중 만료되지 않도록 한다.
전용 스케줄러는 defaultCandidate=false로 등록해 기존 Boot taskScheduler 구성을 대체하지 않는다.

## 장애와 재시도

| 상황 | 처리 |
| --- | --- |
| 연결 실패·타임아웃, HTTP 408/429/5xx | Backoff 후 PENDING 재시도, 한도 도달 시 FAILED |
| HTTP 400/401/403/409 등 요청·인증 오류 | FAILED, 원인 수정 후 운영자가 별도 재처리 판단 |
| 리다이렉트 등 예상하지 않은 HTTP 응답 | FAILED, 키를 다른 주소로 전달하지 않음 |
| 200이지만 소비 기록 응답이 잘못됨 | 재시도, 한도 도달 시 FAILED |
| PUBLISHING 중 프로세스 종료 | lease 만료 후 복구, 한도 도달 시 FAILED |
| 수신 커밋 후 응답 유실·Application 완료 기록 실패 | 같은 messageId 재전송, Notification 소비 기록으로 중복 처리 |

완료·실패 갱신은 status와 leaseToken을 모두 검사한다.
복구 뒤 돌아온 예전 작업자가 새 작업자의 상태를 덮어쓸 수 없다.
소비 기록 기반 중복 처리는 DB 알림 중복을 막는 것이며 외부 전송의 정확히 한 번을 보장하지 않는다.

retryCount는 HTTP 실패 또는 만료 lease 복구 횟수다. 최초 점유 자체로 증가하지 않는다.
이벤트 Worker의 실행 시도 카운트와 구분한다. 실패 예약은 기본 1분부터 지수 증가하고
배수는 최대 64배로 제한한다. 기본 한도는 5회, lease는 1분이다.
이 값들은 개발 검증용 설정 기본값이며 최종 운영 정책 확정으로 간주하지 않는다.
관련 설정은 `yon.notification.publisher.retry-delay`, `max-attempts`, `lease-timeout`,
`batch-size`, `connect-timeout`, `read-timeout`, `poll-delay-ms`로 변경할 수 있다.
lease는 연결+읽기 제한에 5초를 더한 값보다 길어야 한다.
FAILED 자동 재개·관리 API는 추가하지 않았다.

알림 본문·사용자 키·Feign 예외 전문은 Outbox lastError나 로그에 저장하지 않는다.
lastError는 HTTP_503 등 안전한 코드만 사용한다. 성공 흐름의 correlationId·messageId를
Application HTTP 응답 확인과 Notification 소비 로그에 남긴다.

## 로컬 활성화

5단계까지 적용한 DB 테이블을 그대로 사용한다. 추가 DDL은 없다.
두 프로젝트의 `.env`에 아래 항목을 **추가**하고 기존 DB·인증 설정을 유지한다.
기존 backend INTERNAL_API_KEY는 바꾸거나 삭제하지 않는다.

backend/.env:

```properties
NOTIFICATION_PUBLISHER_ENABLED=true
NOTIFICATION_PUBLISHER_TRANSPORT=http
NOTIFICATION_BASE_URL=http://localhost:8081
NOTIFICATION_INTERNAL_API_KEY=<양쪽 서버가 공유하는 새 전용 키>
```

notification-service/.env:

```properties
NOTIFICATION_RECEIVER_ENABLED=true
NOTIFICATION_INTERNAL_API_KEY=<backend와 동일한 새 전용 키>
```

키는 충분히 긴 임의 값으로 생성하고 실제 값을 커밋·로그·PR에 올리지 않는다.
프로젝트별 `notification-http.properties`가 환경변수 별칭을 연결한다.
이는 `.env` 파일을 직접 import하는 기능이 아니다. 개발용 bootRun의 기존 dotenv를 사용하며,
운영 JAR·컨테이너에서는 환경변수/비밀값 관리 수단으로 주입한다.
receiver도 기본 비활성화이며 활성화 후 전용 키가 없으면 기동 실패한다.

Notification을 먼저 실행한 뒤 Application을 실행한다.
Application의 `app.scheduling.enabled`가 false이면 주기 발행이 실행되지 않는다.
날짜 스캔과 사용자별 Outbox 생성의 별도 활성화 설정은 기존 단계 문서를 따른다.
연결 검증을 위해 도메인 스캔/Worker 설정을 임의로 동시에 켜지 않는다.

Notification 포트는 내부 네트워크로만 접근시킨다. 공유 키는 암호화가 아니므로
운영 HTTP는 TLS 또는 신뢰된 암호화 내부 연결을 전제로 하며 외부 ALB에 직접 공개하지 않는다.
현재 localhost URL은 개발 검증용이다. 실제 AWS 네트워크·TLS·배포는 별도 검증 대상이다.

## 검증 순서

1. backend의 `com.team3.backend.outbox.*` 테스트: 기본 비활성, 키 검증, Spring Cloud가 등록한 실제 Feign HTTP,
   타임아웃·응답 검증, PostgreSQL 커밋 경계·점유·Backoff·중단 복구.
2. Notification의 NotificationMessageApiTest와 NotificationReceptionPostgresTest:
   키·브라우저 인증 우회 방지, 오류 상태, 실제 HTTP+제한 계정 DB, 커밋 후 응답과 재수신.
3. 두 프로젝트의 전체 회귀·Boot JAR 빌드를 각각 실행한다.
4. 공용 요청·응답 JSON 및 테스트 SQL·fixture가 운영 JAR에 없는지 확인한다.
5. 양쪽 로컬 서버를 연결하고 실제 Outbox → Notification 저장을 확인한다.

테스트는 상대 프로젝트의 JAR를 요구하지 않는다. Application은 HTTP 검증용 peer,
Notification은 자체 실제 HTTP 서버와 임시 PostgreSQL로 검증하고 공용 JSON 표본으로 계약을 맞춘다.
두 서버의 실제 전체 흐름은 아래 로컬 절차로 별도 확인한다.

### 양쪽 서버 연결 확인

새로 생성된 개발용 날짜 Outbox 또는 아직 발행하지 않은 검증용 PENDING Outbox를 사용한다.
지난 단계의 로컬 데이터가 있으면 Publisher를 켜는 순간 전송될 수 있음을 먼저 확인한다.
운영 데이터나 임의 사용자에게 보내는 테스트를 만들지 않는다.
만료된 Outbox는 PUBLISHED + EXPIRED 소비 기록이 될 수 있으며 인앱 생성 실패가 아니다.

Application 계정으로:

```bash
docker exec -it backend-postgres-db-1 \
  psql -h 127.0.0.1 -U application_user -d yon_boundary -W -P pager=off \
  -c "SELECT user_id, message_id, status, retry_count, last_error, published_at
      FROM application.outbox_events ORDER BY id;"
```

Notification 계정으로:

```bash
docker exec -it backend-postgres-db-1 \
  psql -h 127.0.0.1 -U notification_user -d yon_boundary -W -P pager=off \
  -c "SELECT message_id, user_id, result, notification_id FROM notification.consumed_messages ORDER BY id;
      SELECT user_id, source_event_id, message_id, type, read_at, clicked_at
      FROM notification.notifications ORDER BY id;"
```

messageId를 기준으로 양쪽 기록이 대응하는지 확인한다.

```
Outbox PUBLISHED → ConsumedMessage CREATED → Notification 1건
Outbox PUBLISHED → EXPIRED/CHANNEL_DISABLED → Notification 없음
```

서버 재기동 후에도 기존 PUBLISHED는 다시 전송하지 않는다.
HTTP 성공 후 장애의 자동 검증은 점유 복구 테스트와 수신 재전달 테스트로 수행하며,
실제 DB의 소비 기록을 지우거나 기존 Outbox를 임의로 되돌려 검증하지 않는다.

## 후속 범위

7단계 공개 Facade·목록/읽음/설정/Endpoint, 8단계 FCM·Delivery·탈퇴 Tombstone,
9단계 SQS Standard·DLQ를 이번 단계에 앞당기지 않는다.
SQS 전환 시 같은 Publisher 계약과 수신 Handler를 재사용하되 HTTP와 동시에 발행하지 않는다.
