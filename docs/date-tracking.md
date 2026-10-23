# 날짜 추적 연결 및 중단 스캔 복구 — 3단계

## 범위

Application(backend)에서 날짜 이벤트를 생성·저장한다. Notification 프로젝트는 변경하지 않는다.
별도 feat/opportunity-tracking-notification 브랜치의 감지기·이벤트·이력·테스트만 선택적으로
가져왔으며 브랜치 전체를 병합하거나 기존 수집/정규화 구현을 되돌리지 않았다.

- YOUTH_POLICY의 현재 버전 신청기간을 사용한다.
- 기존 신청 상태 계산기로 정책 전체의 UPCOMING → OPEN, OPEN → CLOSED를 감지한다.
- 유효한 FIXED 신청기간의 D-7·D-3·D-1을 감지한다.
- 상시 정책·훈련 시작일·검증되지 않은 신청기간은 마감 임박 대상에서 제외한다.
- 복수 기간 중 다른 기간이 열려 있으면 정책 전체 마감 이벤트를 만들지 않는다.
- windowId 대신 실제 날짜 기반 dedupKey와 DB UNIQUE로 중복을 막는다.
- 이벤트에 외부 전달용 UUID eventId와 스캔 실행 ID 기반 correlationId를 기록한다.
- 날짜 경계는 주입된 Clock과 KST를 사용한다.
- 날짜 이벤트 occurredAt은 해당 사건 감지 기준일의 KST 00:00이다.
  마감 이벤트는 마감 다음 날 00:00에 발생하며, eventDate/dedupKey에는 실제 마감일을 기록한다.
  nextRetryAt은 실제 생성 시각으로 초기화해 두 시각을 혼동하지 않는다.
- 지난 임계일의 마감 임박 이벤트는 THRESHOLD_ALREADY_PASSED로 억제한다.

이벤트 Worker·사용자 순회·Outbox·내부 HTTP/Feign·SQS·FCM은 구현하지 않는다.
이 단계에서는 사용자에게 알림을 전송하지 않는다.
기존 이벤트 처리 상태/Repository 기반은 재사용하지만 실제 Worker의 점유·완료·복구 소유권은
4단계에서 추가 검증·보강한다.

## 수집·정규화 연결

수집/정규화의 업무 트랜잭션이나 실행 코드를 수정하지 않고 저장된 상태를 폴링한다.
다음 조건을 모두 만족하면 마지노선 이전에도 당일 스캔을 시작한다.

1. 당일 청년정책의 최신 수집 이력이 SUCCESS다.
2. 청년정책 수집의 PENDING/RUNNING 이력이 없다.
3. 당일 종료 전까지 등록된 청년정책 정규화 요청 중 PENDING 요청이 없다.

FAILED 정규화는 종료 상태이므로 기다리지 않는다(재시도되면 새 PENDING 요청으로 다시 대기한다).
부분 수집은 성공으로 간주하지 않는다.
마지노선이 되면 완료 여부와 무관하게 현재 DB의 정규화 데이터로 수행한다.
폴링 방식이므로 수집 완료부터 실행까지 기본적으로 최대 한 폴링 간격 지연이 있다.
과거 날짜의 복구는 당일 수집 완료를 기다리지 않는다.
당일 스캔은 과거 복구보다 먼저, 복구 예산과 별도로 실행해 과거 날짜 실패가 당일 추적을 막지 않게 한다.
이는 원본을 과거 시점으로 되돌리는 기능이 아니며, 스캔 시점의 현재 버전으로 날짜를 재계산한다.

## 실행 이력과 트랜잭션

EventScanHistory는 날짜 UNIQUE로 최초 실행권을 얻고, 실패 또는 lease가 만료된 실행만 재시작한다.
재시작마다 executionId를 교체하고 retryCount를 증가시킨다.
오래된 RUNNING 판정은 페이지 커밋 시각인 updatedAt을 기준으로 한다.

한 정책 페이지 트랜잭션은 다음을 수행한다.

1. 해당 날짜의 이력 행을 쓰기 잠금하고 status·executionId를 확인한다.
2. ID 키셋으로 정책을 조회하고 현재 버전을 읽기 잠금해 페이지 처리 중 변경을 막는다.
3. 현재 버전의 신청기간으로 이벤트를 생성하고 중복 키가 없는 이벤트만 저장한다.
4. 같은 트랜잭션에서 heartbeat를 갱신한다.
5. 더 조회할 정책이 없으면 같은 잠금 아래 SUCCESS로 완료한다.

재점유는 페이지 잠금 해제를 기다리고 최신 heartbeat를 다시 평가한다.
소유권이 바뀐 예전 작업자는 새 페이지 저장·실패·완료를 수행할 수 없다.
한 날짜 전체를 긴 트랜잭션으로 묶지 않는다.
페이지 중단 시 해당 페이지는 롤백되고 이전 커밋은 유지된다.
재시작은 ID 0부터 다시 조회하되 이벤트 UNIQUE/중복 확인으로 이미 저장된 사건을 보존한다.
완료된 날짜는 다시 스캔하지 않는다.

정책 추가/변경 전체의 장시간 스냅샷을 유지하지는 않는다.
스캔이 끝난 뒤 추가된 정책이나 뒤늦게 바뀐 버전은 같은 날짜를 소급 재처리하지 않으며,
버전 추적·기준선은 후속 단계에서 연결한다.

## 설정

자동 실행은 기본 비활성화다. main 진입점이나 Application Security는 변경하지 않는다.
app.scheduling.enabled=false인 경우도 자동 실행하지 않는다.

| 속성 | 기본값 | 용도 |
| --- | --- | --- |
| yon.tracking.date.enabled | false | 자동 실행 |
| yon.tracking.date.start-date | 없음 | 누락 보완의 최초 기준일. 활성화 시 필수 |
| yon.tracking.date.cutoff | 13:00 | KST 마지노선. 확정 운영 시간이 아닌 조정 가능한 초기값 |
| yon.tracking.date.lease-timeout | PT30M | heartbeat 만료 초기값. 운영 처리 시간에 맞게 조정 |
| yon.tracking.date.batch-size | 100 | 정책 페이지 크기 |
| yon.tracking.date.max-dates-per-run | 7 | 한 폴링당 실행권을 얻어 처리할 과거 날짜 수. 당일은 포함하지 않는다 |
| yon.tracking.date.recovery-days | 3 | 오늘 이전 며칠까지 복구할지. 0이면 당일만 스캔한다 |
| yon.tracking.date.poll-interval-ms | 60000 | 폴링 간격 |

당일을 먼저 스캔한 뒤 max(start-date, 오늘 - recovery-days)부터 어제까지의 누락/실패 날짜를 보완하고
SUCCESS 날짜는 건너뛴다. 다른 실행이 잡고 있어 실행권을 얻지 못한 날짜는 예산에 넣지 않는다.
늦게 복구한 날짜의 마감 임박은 THRESHOLD_ALREADY_PASSED로 억제되므로 오래된 날짜를 복구해도
발송할 이벤트가 거의 없다. 그래서 복구 범위를 최근 며칠로 제한하고, 범위를 벗어난 실패 날짜는
더 이상 시도하지 않는다. 기본값 3일은 주말 동안 서버가 멈춘 경우의 신청 시작 이벤트까지 고려한 초기값이다.
자동 실행 활성화 때 최초 추적일을 명시해 서버 재시작으로 복구 시작일이 밀리지 않도록 한다.
기동 후 첫 스케줄부터 실행하고 이후 fixed delay로 반복한다.
ApplicationReadyEvent에서 동기 실행하지 않으므로 복구 스캔이 main 기동 스레드를 점유하지 않는다.
운영값은 배포 전에 팀이 검토한다. 이 문서는 제품 정책이나 운영 수치의 새 확정을 뜻하지 않는다.

## 스키마 적용

backend는 마이그레이션 도구 도입 전까지 ddl-auto=update를 사용한다([DB 경계 문서](database-boundaries.md)의 ddl-auto 정책).
update는 SQL에만 있는 CHECK 제약 등을 만들지 않으므로, Application 기본 테이블이 이미 있는 DB에는
새 버전 서버를 기동하기 **전에** 신규 두 테이블을 명시적 SQL로 먼저 만든다.

1. 서버를 중지하고 대상 DB/백업/기존 객체를 확인한다.
2. Application 기본 테이블이 존재하는 DB에 application_user로 접속한다.
3. database/scripts/date-tracking.sql을 실행한다.
4. 새 버전 서버를 기동하고, 테스트 후 자동 스캔 활성화 여부를 결정한다.

Application 테이블이 하나도 없는 새 로컬 DB는 첫 기동에서 update가 추적 테이블까지 만든다.
이때는 SQL을 실행하지 않는다(테이블이 이미 있으면 SQL이 실패한다). 이 경우 SQL에만 있는 CHECK 제약은 없다.

SQL은 최초 설치용이며 기존 테이블을 삭제하거나 재생성하지 않는다.
이미 추적 테이블이 있으면 실행이 실패한다. 반복 설치나 구 추적 브랜치 DB 업그레이드에 사용하지 않는다.
구 구조의 추적 데이터를 보존하려면 event_id/correlation_id 백필 등 별도 변경안이 필요하다.
운영 또는 이미 초기화한 로컬 DB에서 ddl-auto=create로 재실행하지 않는다.
SQL은 실행 계정 application_user를 검사하며 관리자 계정이면 SET ROLE application_user 후 실행한다.
실제 DB에 적용하는 작업은 이 코드 변경과 별도다.

## 검증

Docker를 실행한 뒤 backend에서 실행한다.

```bash
./gradlew test --rerun-tasks --tests 'com.team3.backend.tracking.*'
```

그다음 신규 DDL·기존 DB 경계와 전체 회귀를 확인한다.

```bash
./gradlew test --rerun-tasks --tests 'com.team3.backend.architecture.DatabaseBoundaryPostgresTest'
./gradlew clean test bootJar
```

기존 날짜 감지/이벤트 테스트에 더해 아래 사례를 검증한다.

- 당일 수집·정규화 완료 전 대기와 마지노선 fallback
- 과거 누락 보완, 성공 날짜 건너뜀, 실패 기록 및 페이지 순회
- 과거 날짜가 계속 실패해도 당일을 먼저 스캔, 복구 범위 밖 날짜 제외, 실행권을 얻은 날짜만 예산 차감
- 오래된 RUNNING 재점유와 예전 실행의 저장/완료/실패 차단
- 페이지 잠금 중 재점유 대기 및 커밋된 heartbeat 존중
- 부분 커밋 후 재시작의 이벤트 중복 방지
- 현재 버전·정책 유형·신청기간 검증 상태 경계
- 과거 마감 임박 억제, eventId/correlationId 및 발생·재시도 시각
- 신규 SQL의 소유권·필수 컬럼·재실행 실패 시 기존 데이터 보존

테스트는 임시 PostgreSQL에서 실행하며 실제 개발/운영 DB에 SQL을 적용하지 않는다.
Notification은 변경하지 않지만 독립 프로젝트의 기존 테스트/빌드도 회귀 확인한다.
이 문서의 목록은 테스트 코드의 검증 대상이며, 실제 실행 성공 여부는 실행 결과로 확인한다.
