# Notification 수신·소비 기록·인앱 저장 — 개정안 5단계

## 범위

Notification의 독립 프로젝트 안에 `NotificationMessageHandler`를 추가한다.
Application의 사용자별 Outbox JSON을 검증해 인앱 알림과 소비 기록을 저장하는 내부 서비스다.
아직 HTTP Controller나 큐 Listener에 연결하지 않았으므로 서버를 켜는 것만으로
Application의 PENDING Outbox가 전송되거나 소비되지 않는다.
모든 HTTP 요청 거부 Security, 독립 빌드와 DB 제한 계정을 유지한다.

이번 단계에서 지원하는 요청은 schemaVersion=1 / eventType=NOTIFICATION_REQUESTED와
APPLICATION_OPENED, APPLICATION_CLOSED, DEADLINE_SOON 유형이다.
지원하지 않는 버전·유형·USER_WITHDRAWN은 거부하며 소비 성공으로 기록하지 않는다.
미래의 선택적 JSON 필드는 무시한다. 필수 필드, UUID, 양의 정수 ID, 채널, 만료 시각,
내부 경로 링크를 검증하며 Application DB에서 사용자·정책을 다시 조회하지 않는다.
제목/링크 저장 한도는 각각 500/2000자이며 본문은 TEXT다.

## 현재 수신 흐름

1. 형식·버전·필수 값 검증
2. messageId 소비 기록 확인: 동일 요청이면 기존 결과 반환
3. 현재 시각이 expiresAt 이상이면 EXPIRED 소비 기록만 저장
4. 요청 채널에 IN_APP이 없으면 CHANNEL_DISABLED 소비 기록만 저장
5. `(userId, sourceEventId)`가 기존 알림이면 DUPLICATE_EVENT, 없으면 인앱 알림 생성
6. 소비 기록 저장 후 같은 트랜잭션에서 커밋

인앱 기본 허용·푸시 기본 비허용만 적용한다. IN_APP+PUSH 요청도 현재는 인앱만 저장한다.
사용자 설정 저장·조회 API는 7단계, Endpoint·Delivery·FCM·탈퇴 Tombstone 처리는 8단계에서
연결한다. 그러므로 현재 Handler는 최종 수신 흐름의 완성본이 아니며,
사용자 설정과 탈퇴 차단까지 지원한다고 간주해 운영 발송 경로를 개방하면 안 된다.
6단계에서 인증된 HTTP 수신과 Publisher를 연결하고, 9단계에서 SQS 커밋 후 삭제를 연결한다.

expiresAt은 Application이 계산한 배타적 만료 Instant를 그대로 사용한다.
사용자의 저장 상태나 정책 변경을 Notification에서 재판정하지 않는다.
이 단계에는 읽음·클릭 처리 API, 알림 삭제 API, 소비 기록 정리 작업도 없다.

## 저장과 멱등성

| 테이블 | 주요 제약 | 책임 |
| --- | --- | --- |
| notification.notifications | UNIQUE(user_id, source_event_id) | 인앱 메시지, 추적 ID, 발생·만료·생성 시각, 최초 미읽음·미클릭 상태 |
| notification.consumed_messages | UNIQUE(message_id) | 수신 결과, 요청 지문, 소비 시각, 선택적 알림 참조 |

사용자/정책/버전 ID는 스칼라로 보관하며 Application 스키마 FK는 두지 않는다.
소비 기록의 알림 참조만 Notification 내부 FK로 두고 ON DELETE SET NULL로 기록을 보존한다.
이는 삭제·탈퇴 기능을 구현했다는 의미가 아니다. 기록 보존·정리 기간은 별도 확정한다.

같은 messageId가 서로 다른 알려진 본문으로 재사용되면 오류로 처리한다.
요청 지문은 JSON 객체 키와 채널 순서에 영향받지 않으며 무시한 선택적 필드는 포함하지 않는다.
다른 messageId로 같은 사용자·이벤트가 와도 인앱 알림은 하나다.
EXPIRED/CHANNEL_DISABLED 역시 소비 기록을 남겨 반복 처리를 막는다.

알림 저장과 소비 기록 저장은 별도 빈의 REQUIRES_NEW 트랜잭션 하나로 처리한다.
고유 제약 충돌 시 실패한 트랜잭션을 완전히 롤백한 뒤 새 트랜잭션으로 다시 조회한다.
SQLSTATE 23505이면서 위 두 UNIQUE의 이름이 확인된 경우만 한 번 복구를 시도한다.
다른 FK·CHECK·NOT NULL·알 수 없는 무결성 오류는 성공으로 숨기지 않는다.
새 트랜잭션에서도 오류가 나면 호출자에게 전파하며, 전송 계층 재시도는 이후 단계 책임이다.
외부 통신은 이 트랜잭션 안에서 수행하지 않는다.

## DB 적용

먼저 [DB 경계 설정](database-boundaries.md)의 계정·스키마를 준비한다.
Notification 서버를 중지하고 대상 DB와 기존 객체·백업을 확인한 뒤
`database/scripts/notification-inbox.sql`을 **notification_user**로 한 번 적용한다.
Application 테이블이나 데이터·권한은 변경하지 않는다. 스크립트는 기존 테이블을
지우거나 자동 보정하지 않으며 재적용 시 오류와 롤백으로 중단한다.
운영 DDL 자동 실행이나 ddl-auto=update 우회는 도입하지 않는다.

현재 로컬 구성에서 저장소 루트 기준:

```bash
docker cp database/scripts/notification-inbox.sql \
  backend-postgres-db-1:/tmp/yon-notification-inbox.sql

docker exec -it backend-postgres-db-1 \
  psql -h 127.0.0.1 -U notification_user -d yon_boundary -W \
  -v ON_ERROR_STOP=1 -f /tmp/yon-notification-inbox.sql
```

실제 환경의 DB명·컨테이너명으로 확인해 바꾼다. 비밀번호는 프롬프트에 입력하고 커밋하지 않는다.
이후 notification-service에서 bootRun을 실행하면 Hibernate validate가 테이블 일치를 검사한다.
새 테이블 적용 전에는 validate 실패가 정상이며 테이블 자동 생성으로 우회하지 않는다.

## 계약·테스트

`contracts/notification-requested-v1.json`은 공유 Java 모듈이 아닌 테스트용 JSON 표본이다.
양쪽 processTestResources에만 복사하며 운영 JAR에는 포함하지 않는다.
Application의 기존 메시지 Factory 결과와 표본을 비교하고,
Notification은 같은 표본을 자체 DTO로 읽어 검증한다. 기존 Factory의 런타임 코드는 변경하지 않는다.
contracts 변경만으로도 양쪽 PR CI가 실행되도록 경로 필터를 추가한다.

Notification 디렉터리에서 Docker가 실행 중인 상태로:

```bash
./gradlew test --rerun-tasks \
  --tests 'com.team3.notification.message.*' \
  --tests 'com.team3.notification.service.*'
```

- 계약 검증: 버전/필드/타입/링크 오류, 선택적 필드, 요청 지문 정규화
- Handler 단위 검증: 알려진 UNIQUE만 복구, 다른 무결성 오류·두 번째 실패 전파
- PostgreSQL 검증: 실제 수동 SQL + Hibernate validate + notification_user로 수신
- 동일 메시지 재수신, 다른 메시지의 동일 사용자·이벤트, 동시 수신의 최종 개수
- 만료 경계·채널 비활성 소비 기록, 저장 실패 전체 롤백, 재시도 가능 상태
- 스키마 간 접근 차단·FK 부재, 소비 기록 보존, DDL 재적용 시 데이터 보존

PostgreSQL 테스트는 Testcontainers의 별도 임시 DB만 사용한다.
로컬 yon_boundary/yon_test 데이터나 .env 비밀번호를 테스트 준비에 사용하지 않는다.

이어서 Notification 전체 test/bootJar, backend의 DateNotificationMessageFactoryTest와
기존 전체 회귀를 각각 실행한다. 두 프로젝트가 자동으로 함께 실행되는 구조는 아니다.
JAR 안의 contracts/, db-boundary/, 테스트 fixture, dotenv 부재도 확인한다.
로컬 기획 문서와 .env는 커밋 대상이 아니다.
