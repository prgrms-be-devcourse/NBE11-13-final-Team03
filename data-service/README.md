# DataService

외부 데이터를 **수집하고 정규화해서 공고 테이블에 쓰는 서비스**입니다. 판정·검색·추천은 메인 서비스(backend)에 있습니다.

| 하는 일 | 패키지 |
|---|---|
| 고용24·온통청년 수집, 원본 저장, 정규화 요청 생성 | `collection` |
| 정규화 요청 처리 (LISTEN/NOTIFY, 1분 폴링, 1시간 대조) | `opportunity.ingest` |
| 정규화 규칙·서비스, 공식 원문 보완 | `opportunity.normalization`, `opportunity.service` |
| 조건 묶음 생성·백필 | `opportunity.conditionset` |
| 지역 사전 동기화 | `region` |

- DB는 메인 서비스와 공유합니다 (설계: 수집·정규화 서비스 분리 설계 문서). 접속 계정·스키마는 아래 DB 경계를 따릅니다.
- `db/scripts/normalization-notifications.sql`: 정규화 요청 NOTIFY 트리거. 배포·초기화 계정으로 한 번 실행합니다 (앱이 자동 실행하지 않음).

## 실행 플래그 (메인 서비스와 함께 띄울 때)

메인 서비스(backend)에서 수집·정규화 코드를 걷어내기 전까지는 같은 스케줄러·기동 작업이 양쪽에 있습니다.
**아래 4개는 두 서비스 중 한쪽에서만 `true`** 로 둡니다. data-service를 띄우면 메인 서비스 쪽을 모두 `false`로 둡니다.

| 환경변수 | 하는 일 | 양쪽에서 켜면 | data-service | 메인 서비스 |
|---|---|---|---|---|
| `COLLECTION_SCHEDULE_ENABLED` | 정기 수집 (매일 09:30) | 출처 잠금으로 동시 실행은 막히지만, 한쪽이 끝난 뒤 다른 쪽이 또 수집 | `true` | `false` |
| `YON_NORMALIZATION_INGEST_ENABLED` | 정규화 요청 처리 (LISTEN/NOTIFY, 1분 폴링, 1시간 대조) | 요청은 SKIP LOCKED라 중복 처리는 없지만 같은 일을 두 곳에서 함 | `true` | `false` |
| `YON_NORMALIZATION_CONDITION_SET_BACKFILL_ENABLED` | 기동 시 조건 묶음 백필 | 동시 기동하면 같은 묶음을 만들다 경합 | `true` | `false` |
| `REGION_CODE_SYNC_ON_STARTUP` | 기동 시 지역 사전 동기화 | 같은 동기화를 두 번 | 필요할 때만 `true` | `false` |

- 기본값은 모두 `true`입니다 (지역 동기화만 `false`). 메인 서비스 기본값은 수집·정규화를 걷어내는 PR에서 코드와 함께 없앱니다.
- 값 예시는 `.env.example`.

## DB 경계

- `main()`으로 실행하면 `database-boundary` 프로필이 붙어 메인 서비스와 같은 **`application_user` 계정, `application` 스키마**로 접속합니다 (`application-database-boundary.yaml`). 비밀번호는 `APPLICATION_DB_PASSWORD`.
- 기동할 때 계정·스키마·`public` 미이전 테이블 등을 검사하고 어긋나면 뜨지 않습니다 (`DataServiceDatabaseBoundaryConfig`, 메인 서비스 `ApplicationDatabaseBoundaryConfig`와 같은 검사). `postgres` 계정으로 떠서 `public`에 빈 테이블을 만드는 일을 막습니다.
- 스키마 변경(`ddl-auto: update`)은 테이블을 소유한 서비스가 합니다. 소유 표와 규칙은 `docs/database-boundaries.md`.
- 테스트는 `main()`을 거치지 않아 테스트용 DB 설정(H2, Testcontainers)을 그대로 씁니다.

## 테스트
```bash
yon-test data
```
PostgreSQL 테스트는 `YON_TEST_DB_URL`이 있을 때만 돕니다.
