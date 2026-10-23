# DataService

외부 데이터를 **수집하고 정규화해서 공고 테이블에 쓰는 서비스**입니다. 판정·검색·추천은 메인 서비스(backend)에 있습니다.

| 하는 일 | 패키지 |
|---|---|
| 고용24·온통청년 수집, 원본 저장, 정규화 요청 생성 | `collection` |
| 정규화 요청 처리 (LISTEN/NOTIFY, 1분 폴링, 1시간 대조) | `opportunity.ingest` |
| 정규화 규칙·서비스, 공식 원문 보완 | `opportunity.normalization`, `opportunity.service` |
| 조건 묶음 생성·백필 | `opportunity.conditionset` |
| 지역 사전 동기화 | `region` |

- DB는 메인 서비스와 공유합니다 (설계: 수집·정규화 서비스 분리 설계 문서).
- `db/scripts/normalization-notifications.sql`: 정규화 요청 NOTIFY 트리거. 배포·초기화 계정으로 한 번 실행합니다 (앱이 자동 실행하지 않음).

## 내부 API

| 메서드 | 경로 | 메인 서비스 관리자 API |
|---|---|---|
| POST | `/internal/collection/syncs/{source}` | `/api/admin/collection/syncs/{source}` |
| GET | `/internal/collection/syncs?date=` | `/api/admin/collection/syncs?date=` |
| GET | `/internal/collection/syncs/{jobId}` | `/api/admin/collection/syncs/{jobId}` |
| POST | `/internal/collection/raw-records` | `/api/admin/collection/raw-records` |
| GET | `/internal/collection/raw-records/{id}` | `/api/admin/collection/raw-records/{id}` |

- 모든 요청에 `X-INTERNAL-KEY` 헤더가 필요합니다 (`DATA_SERVICE_INTERNAL_API_KEY`). 없거나 다르면 401 `AUTH_008`.
- 관리자 권한 확인은 메인 서비스가 하고 넘어옵니다. 수동 수집 시작의 `Location`은 `/internal/...` 경로라 메인 서비스가 관리자 경로로 바꿔서 돌려줍니다.

## 배포

- 포트 `8082`. Spring Security가 없는 내부 전용 서비스라 **8082는 내부 네트워크(메인 서비스)에서만 접근 가능해야 합니다.** 보안 그룹·compose에서 외부로 열지 않습니다.
- 환경변수: `DB_URL`·`DB_USERNAME`·`DB_PASSWORD`(메인과 같은 DB), `DATA_SERVICE_INTERNAL_API_KEY`, `EMPLOYMENT24_API_KEY`, `YOUTH_POLICY_API_KEY`, `REGION_CODE_API_KEY`.

### 전환 순서 (메인 서비스에서 수집·정규화를 옮기는 동안)

| 순서 | 할 일 | 수집·정규화를 실행하는 쪽 |
|---|---|---|
| 0 | 지금 (data-service 미배포) | 메인 |
| 1 | data-service 배포, **메인의 수집 스케줄러·정규화 연동 끔** (`collection.schedule.enabled=false`, `YON_NORMALIZATION_INGEST_ENABLED=false`) | data-service |
| 2 | 메인 관리자 API를 data-service 프록시로 교체 (`DATA_SERVICE_BASE_URL`, `DATA_SERVICE_INTERNAL_API_KEY`) | data-service |
| 3 | 메인에서 수집·정규화 코드 삭제 | data-service |

- 1단계 전에는 data-service를 띄우더라도 `YON_NORMALIZATION_INGEST_ENABLED=false`, `collection.schedule.enabled=false`로 둡니다 (두 서비스가 같은 스케줄·대조를 중복 실행하지 않게).
- 2·3단계는 메인 서비스 PR(수집 관리자 API 전달, 정규화 제거)로 진행합니다.

## 테스트
```bash
yon-test data
```
PostgreSQL 테스트는 `YON_TEST_DB_URL`이 있을 때만 돕니다.
