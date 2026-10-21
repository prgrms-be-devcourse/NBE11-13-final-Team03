# 수집 정제 요청 NOTIFY

## 역할과 연동 계약

PR #10은 정제 요청 등록 시 PostgreSQL 트리거로 NOTIFY를 발행한다.
LISTEN·요청 선점·정규화·폴링·대조는 main에 병합된 PR #21의 정책 엔진이 담당한다.
수집 영역에는 별도 LISTEN 연결이나 Spring 이벤트 중계가 없다.

| 항목 | 계약 |
| --- | --- |
| 영속 처리 대상 | `raw_normalization_request`의 PENDING 요청 |
| 채널 | `raw_normalization_requested` |
| payload | `raw_normalization_request.id`의 문자열. 원본 레코드 ID가 아님 |
| 발행 시점 | 요청 INSERT 트랜잭션의 커밋 후. 롤백 시 미전달 |
| 수신부 | `opportunity/ingest/RawIngestListener`가 DB 채널을 직접 구독 |
| 처리 | payload를 사용하지 않고 PENDING 요청을 조회·선점하여 SUCCESS/FAILED로 갱신 |

발행부와 수신부는 같은 PostgreSQL 데이터베이스를 사용해야 한다.
수신 채널 설정을 바꾸면 트리거의 채널도 함께 변경해야 한다.
알림 횟수는 처리 건수가 아니며, 실제 처리 대상은 영속 요청과 원본 데이터로 판단한다.
수집·원본 저장과 요청 등록 트랜잭션의 경계는 이 PR에서 변경하지 않는다.

## 함수·트리거 설치

앱 시작 시 DDL을 실행하지 않는다. 테이블 생성 후 배포/초기화 계정으로 별도 적용한다.

```sh
psql -v ON_ERROR_STOP=1 -f backend/src/main/resources/db/scripts/normalization-notifications.sql
```

접속 정보는 `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER` 및 안전한 인증 설정으로 제공한다.
SQL은 BEGIN/COMMIT으로 설치를 원자적으로 수행하며 반복 적용할 수 있다.
여러 앱 인스턴스가 시작할 때마다 설치하지 않는다. 앱 실행 계정에는 DDL 생성 권한이 필요하지 않다.
트리거가 설치된 이후에는 요청 INSERT 시 자동 발행하며 별도의 앱 발행 활성화 설정은 없다.
트리거 미설치 시에도 PR #21의 요청 폴링·원본 대조는 동작하지만 즉시 알림은 발행되지 않는다.

## 수신·정제 설정과 복구

다음 설정은 PR #21의 정책 엔진 소유이며 이 PR에서 변경하지 않는다.

| 설정/동작 | 기본값과 의미 |
| --- | --- |
| `YON_NORMALIZATION_INGEST_ENABLED` | true. false면 정책 엔진의 LISTEN·폴링·대조를 비활성화 |
| `yon.normalization.ingest.channel` | `raw_normalization_requested` |
| `listen-wait-ms` | 30,000ms. 알림 수신 대기이며 PENDING 폴링 주기가 아님 |
| 요청 폴링 | 시작 30초 후, 이후 완료 시점부터 1분 간격 |
| 원본 대조 | 시작 5분 후, 이후 완료 시점부터 1시간 간격 |
| 연결 복구 | 5초 후 재연결하며 LISTEN 등록 직후 PENDING 처리 |

소비자는 Hikari 풀과 별도의 전용 JDBC 연결을 사용하고, `FOR UPDATE SKIP LOCKED`로 요청을 선점한다.
FAILED 요청은 그대로 두고 주기적 대조에서 일시 오류로 미처리된 최신 원본을 복구한다.
데이터 오류로 INVALID가 기록된 동일 해시는 대조에서 다시 처리하지 않는다.

기존 `collection.normalization.enabled`/`NORMALIZATION_ENABLED` 및 수집 Listener를 제거했다.
`app.role`은 PR #21의 소비자 활성화 조건이 아니다. 역할별 배포에서는
정제를 실행하지 않는 인스턴스에 `YON_NORMALIZATION_INGEST_ENABLED=false`를 명시한다.
소비자를 꺼도 이미 설치된 DB 트리거는 NOTIFY를 계속 발행한다.

## 검증과 CI

`NormalizationNotificationTriggerTests`가 실제 PostgreSQL에서 설치 SQL 반복 적용,
한 트랜잭션의 여러 요청 ID 전달, 커밋 전 미전달·커밋 후 전달·롤백 미전달을 검증한다.
테스트용 LISTEN 연결은 발행 결과를 관찰하기 위한 것으로 운영 수신 구현이 아니다.

`.github/workflows/collection-postgres-tests.yml`은 main 대상 backend PR에서 Docker를 확인한 뒤
`./gradlew clean test`를 실행하고, 트리거 테스트 XML의 실행 건수 > 0 및 실패·오류·건너뜀 = 0을 확인한다.
Docker가 없으면 트리거 테스트를 건너뛰지 않고 실패한다.
정제 처리 검증은 PR #21의 `RawIngestProcessorTest`가 담당하며 `YON_TEST_DB_URL`이 필요하다.
현재 CI의 트리거 테스트 성공만으로 발행부터 실제 정규화까지 전체 연동 검증을 완료했다고 보지 않는다.

## 메시징 선택

| 흐름 | 영속 처리 대상 | 신호와 선택 이유 |
| --- | --- | --- |
| 수집 → 정제 | `raw_normalization_request` | 같은 DB의 트랜잭션에 요청을 보존하고 LISTEN/NOTIFY로 조회 지연을 줄인다. 알림 유실은 요청 폴링과 원본 대조로 보완한다. |
| 정책 버전 변경 → 추적·알림 서버 | 설계상 Outbox | 별도 서버의 재시도·확장을 분리하기 위해 SQS를 사용하려는 흐름이다. DB와 전송 사이의 정합성은 Outbox 및 멱등 소비로 보완해야 하며, 이 PR에서 구현하지 않는다. |
