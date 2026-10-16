# 수동 데이터 최신화 API

관리자(`ROLE_ADMIN`)가 온통청년 또는 고용24의 원본 데이터를 비동기로 최신화한다. 기존 인증과 CSRF 보호를 유지한다. 원본 저장까지만 수행하며 정책 정제·버전 생성·알림은 실행하지 않는다.

## 시작과 조회

```http
POST /api/admin/collection/syncs/YOUTH_POLICY?pageSize=100&maxPages=1000
POST /api/admin/collection/syncs/EMPLOYMENT24?pageSize=100&maxPages=1000
```

요청 본문은 없다. `pageSize`는 1~100(기본 100), `maxPages`는 1~1000(기본 1000)이다. 고용24의 페이지 상한은 NCS별로 적용되며, 한국 날짜 기준 오늘부터 6개월 후까지 NCS 01~24를 조회한다.

요청을 접수하면 수집 실행을 DB에 먼저 커밋한 뒤 실행기에 전달하고 `202 Accepted`를 반환한다. `Location`은 조회 주소다.

```json
{
  "jobId": 10,
  "source": "YOUTH_POLICY",
  "status": "PENDING",
  "message": "수동 최신화 요청을 접수했습니다."
}
```

```http
GET /api/admin/collection/syncs/10
```

조회는 `SyncJobResponse`를 반환한다. 응답의 접수 상태가 PENDING이어도 작업은 이미 RUNNING 또는 종료 상태일 수 있으므로 조회 결과를 기준으로 판단한다.

| 필드 | 의미 |
| --- | --- |
| `status` | PENDING → RUNNING → SUCCESS / PARTIAL_SUCCESS / FAILED, 또는 INTERRUPTED |
| `startedAt`, `completedAt` | 접수 시각, 종료 시각 |
| `fetchedCount` | 페이지 수집 결과로 얻은 항목 수. 페이지 중복도 포함 |
| `successCount` | 커밋된 신규·수정·동일 항목의 합 |
| `newCount`, `updatedCount`, `unchangedCount` | 최초 관측·직전 원본 변경·동일 원본 건수 |
| `failedRecordCount`, `pendingCount` | 항목 처리 실패 수, 수집된 항목 중 아직 처리 결과가 없는 수 |
| `failedPageCount` | 두 출처의 실패 페이지 목록 길이 합 |
| `pageFailures` | 온통청년의 실패 페이지·오류 코드·재시도 가능 여부 |
| `employment24PageFailures` | 고용24의 실패 NCS·조회 기간·페이지 조건과 오류 |
| `incompleteNcsCodes` | 페이지 순회를 완료하지 못한 고용24 NCS |
| `recordFailures` | 실패 항목의 외부 ID(추출 가능 시), 수집 결과 내 0부터 시작하는 인덱스, 오류 코드 |
| `failureReason` | 종료 실패·중단의 안전한 요약 |
| `totalCount` | 기존 수집 결과가 정확한 전체 건수를 제공하지 않아 null |

기존 수집 서비스를 재사용하므로 페이지 조회 중에는 RUNNING과 처리 건수 0이 표시될 수 있다. 페이지 수집이 끝나면 `fetchedCount`와 페이지 오류가 반영되고, 이후 항목별 커밋마다 처리 건수가 갱신된다. 진행률 100%나 외부 데이터 무누락을 의미하지 않는다.

`SUCCESS`는 순회 완료·항목 및 페이지 실패 없음·미처리 없음일 때만 사용한다. 일부 항목을 정상 처리했지만 실패나 페이지 상한이 남으면 `PARTIAL_SUCCESS`, 정상 처리 없이 수집이 실패하면 `FAILED`다. 정상적인 빈 목록 수집은 `SUCCESS`다. DB의 `collected_count`는 fetchedCount, `failed_count`는 항목 실패 수, `changed_count`는 신규+수정 건수다.

## 식별·변경 비교

- 온통청년은 문자열 `plcyNo`를 외부 ID로 사용한다.
- 고용24는 문자열 `trprId`와 `trprDegr`를 JSON 배열 문자열로 결합한다. 예: `["course-1","01"]`. 구분자가 들어간 ID의 충돌과 다른 회차의 병합을 방지하며, 선행 0을 임의로 제거하지 않는다. 필드 의미는 [고용24 공식 API 명세](https://www.work24.go.kr/cm/e/a/0110/selectOpenApiSvcInfo.do?fullApiSvcId=000000000000000000000000000004)를 기준으로 한다.
- 식별자가 없거나 문자열이 아니면 해당 항목을 실패로 기록하고 나머지는 계속 처리한다.
- 비교 해시는 `sha256-json-v1:` 뒤에 SHA-256 값을 붙인다. 객체 키를 재귀적으로 정렬하고 배열 순서·값 타입·null·빈 문자열은 유지한다. 원본 payload는 변형하지 않는다.
- 과거 원본도 현재 해시 규칙으로 다시 비교하므로 해시 규칙 차이만으로 변경 스냅샷을 만들지 않는다. A→B→A는 변경 두 번으로 보존한다.

## 정합성과 실패 처리

출처가 없으면 설정된 API URL로 최초 등록한다. 기존 출처의 설정과 enabled 값은 덮어쓰지 않는다. 사용 중지된 출처는 실행하지 않는다.

출처 행을 잠근 상태에서 기존 PENDING/RUNNING 실행을 확인하므로 같은 출처의 동시 요청은 하나만 접수된다. 서로 다른 출처는 최대 2개 작업을 병렬 실행한다. API 호출과 전체 순회에 DB 트랜잭션이나 행 잠금을 유지하지 않는다. 원본 저장과 성공 건수 갱신은 항목별 하나의 트랜잭션이며, 실패 기록은 롤백 이후 별도 트랜잭션으로 남긴다.

HTTP 401·403은 두 출처 모두 순회를 중단한다. 그 외의 페이지 재시도·순회 규칙은 기존 수집 서비스를 따른다. 수집 결과의 API 키·URL·원본 예외 메시지는 실패 이력에 저장하지 않는다. 항목 실패는 `INVALID_RECORD` 또는 `STORAGE_FAILED`로 기록한다.

원본 저장 성공은 정제 완료나 사용자에게 알릴 정책 변경 확정을 뜻하지 않는다. 수동 최신화 이력과 오류는 DB에 남지만, 항목별 성공 관측 이력이나 실패 원본 자체를 별도 테이블에 보관하지 않는다. 실패 후 재시도는 출처 전체를 다시 수집하는 방식이다.

시작 요청의 오류는 잘못된 출처·범위 400, 권한·CSRF 401/403, 동일 출처 실행 중 또는 비활성 출처 409, 실행기 거절 503이다. 이미 접수한 작업의 수집 실패는 상태 조회의 FAILED/PARTIAL_SUCCESS로 확인한다. 실행기 거절도 생성된 이력을 FAILED로 마감한다.

## DB 변경과 재시작

기존 PostgreSQL DB에는 앱 시작 전 [sql/manual-sync.sql](sql/manual-sync.sql)을 적용한다. 기존 이력의 신규·수정·동일 세부 건수는 소급 복원하지 않는다. 새 API에서 생성한 실행부터 세부 건수를 보장한다. 테스트는 JPA create-drop으로 별도 전용 DB를 생성한다.

현재 구현은 **단일 애플리케이션 인스턴스** 기준이다. 재시작 시 이번 프로세스 시작 전에 접수된 미완료 MANUAL 실행을 INTERRUPTED로 마감한다. 이미 커밋된 원본과 처리 건수는 유지하며 자동 재실행하지 않는다. 다중 인스턴스로 확장할 때는 실행 소유권과 임대 기반 복구로 교체해야 한다.

## 검증

`ManualSyncControllerIntegrationTests`는 외부 API만 로컬 HTTP 서버로 대체하고 실제 컨트롤러·비동기 실행기·클라이언트·서비스·JPA·PostgreSQL을 연결한다. 실행 환경 설정은 [collection-api.md](collection-api.md)의 전용 DB 테스트 방법과 같다. 운영 API 인증키나 실제 외부 API 호출은 필요하지 않다.
