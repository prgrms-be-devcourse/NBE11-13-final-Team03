# 수집 정제 요청 알림

## 구현 범위와 소비자 후속 작업

이번 PR은 PostgreSQL 알림 수신과 `NormalizationRequestsAvailable` Spring 이벤트 발행까지 구현한다.
현재 이 이벤트를 구독하여 PENDING 요청을 조회·선점·처리하는 정제 소비자는 구현되어 있지 않다.
알림 수신만으로 정제 처리가 시작되거나 미처리 요청이 복구되지는 않는다.

정제 영역의 후속 PR에서 소비자 구현과 정규화 서비스 연결을 담당한다. 후속 PR 번호는 아직 정해지지 않았다.
완료 기준은 시작·재연결·주기 신호 및 NOTIFY 수신 시 PENDING 조회, 동시 선점 방지,
성공·실패 상태 기록, 재시작 후 미처리 요청 복구 검증이다.
Spring 이벤트는 ID 없는 깨우기 신호이며 DB 채널을 직접 구독하는 별도 프로세스는 payload의 요청 ID를 사용할 수 있다.
소비자는 알림 횟수에 의존하지 않고 영속 요청을 기준으로 멱등 처리해야 한다.

## 활성화와 서버 역할

| 설정 | 값 |
| --- | --- |
| `collection.normalization.enabled` / `NORMALIZATION_ENABLED` | 기본 `false`. 소비자 준비 후 명시적으로 `true` |
| `app.role` / `APP_ROLE` | `api`, `all`에서만 허용. 미설정 시 `all` |
| PostgreSQL 이외의 DB | 수신 시작 제외 |

예를 들어 `APP_ROLE=tracking`에서는 enabled가 true여도 Listener를 생성하지 않는다.
현재 역할 조건은 이 Listener에만 적용하며 다른 컴포넌트의 서버 역할을 변경하지 않는다.

## 함수·트리거 설치

앱 시작 시 DDL을 실행하지 않는다. 다음 SQL을 테이블 생성 이후 배포/초기화 계정으로 한 번 적용한다.

```sh
psql -v ON_ERROR_STOP=1 -f backend/src/main/resources/db/scripts/normalization-notifications.sql
```

접속 정보는 `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER` 및 안전한 인증 설정으로 제공한다.
이 스크립트는 `BEGIN`/`COMMIT`으로 설치를 원자적으로 수행하고 반복 적용할 수 있다.
여러 서버가 시작할 때마다 실행하지 않으며 앱 실행 계정에는 함수·트리거 생성 권한이 필요하지 않다.
이미 설치된 트리거는 Listener를 비활성화해도 유지된다.
현재 프로젝트에는 DB 마이그레이션 도구가 없으므로 별도 SQL을 사용한다.

채널은 `raw_normalization_requested`, payload는 `raw_normalization_request.id` 문자열이다.
트리거가 `NEW.id::text`를 전달하며 원본 레코드 ID는 전달하지 않는다.
원본·정제 요청 저장 트랜잭션의 커밋 후 알림이 전달되고 롤백 시 전달되지 않는다.
수집·저장 흐름과 항목별 커밋 단위는 변경하지 않는다.

## 연결과 복구 신호

`PGSimpleDataSource`로 일반 HikariCP 풀과 독립적인 비풀링 연결 하나를 유지한다.
접속 정보는 `spring.datasource.url`, `username`, `password`를 사용한다.
SSL 등 JDBC 옵션은 URL에 지정한다. Hikari 전용 `data-source-properties`는 복사하지 않는다.
일반 풀의 가용 연결 수는 줄지 않지만 PostgreSQL 서버 연결은 인스턴스마다 하나 더 필요하다.

- LISTEN 등록 직후 이벤트 발행
- NOTIFY 수신 시 이벤트 발행
- 마지막 신호 이후 30초가 지나면 이벤트 발행
- 연결 실패 시 5초 후 재연결, LISTEN 재등록 직후 다시 신호 발행

종료 시 1초 단위 수신 대기에서 종료 요청을 확인하고 Worker가 `UNLISTEN` 후 실제 연결을 닫는다.
종료 대기는 2초이며 이후에도 진행 중이면 Worker를 interrupt한다.
연결 장애로 `UNLISTEN`이 실패해도 try-with-resources로 물리 연결 종료를 시도한다.
세션이 종료되면 PostgreSQL도 해당 세션의 LISTEN 등록을 해제한다.

## 검증과 CI

`NormalizationNotificationListenerTests`는 Docker가 없으면 실패한다. 자동 건너뛰기를 허용하지 않는다.
설치 SQL 반복 적용, 커밋 전 미전달·커밋 후 요청 ID 전달·롤백 미전달,
실제 세션 강제 종료 후 재구독 신호와 최종 물리 연결 정리를 검증한다.
단위 테스트는 소비자 예외 후 재전달, 비풀링 설정, UNLISTEN/close 순서 및 역할 조건을 검증한다.

`.github/workflows/collection-postgres-tests.yml`은 main 대상 backend PR에서 Docker를 확인한 뒤
`./gradlew clean test`를 실행한다. 알림 통합 테스트의 XML에서 실행 건수 > 0,
실패·오류·건너뜀 = 0을 추가 확인하고 전체 테스트 리포트를 보관한다.
별도 DB 환경변수가 필요한 기존 테스트·벤치마크는 원래 조건을 유지한다.

## 메시징 선택

| 흐름 | 영속 처리 대상 | 신호와 선택 이유 | 현재 범위 |
| --- | --- | --- | --- |
| 수집 → 정제 | `raw_normalization_request` | 같은 PostgreSQL 트랜잭션에서 요청을 보존하고 LISTEN/NOTIFY로 조회 지연을 줄인다. 추가 브로커 없이 DB 조회로 복구 가능하도록 설계한다. | 요청 저장·알림 발행/수신까지. 소비자 후속 구현 필요 |
| 정책 버전 변경 → 추적·알림 서버 | 설계상 Outbox | SQS는 별도 서버로 작업을 전달하고 재시도·확장을 분리하려는 용도다. DB와 SQS 전송 사이의 정합성은 Outbox 발행·재시도와 소비자 멱등성으로 보완해야 한다. | 이번 PR에서 구현·검증하지 않음 |

동작 근거: [PostgreSQL LISTEN](https://www.postgresql.org/docs/18/sql-listen.html),
[UNLISTEN](https://www.postgresql.org/docs/18/sql-unlisten.html).
