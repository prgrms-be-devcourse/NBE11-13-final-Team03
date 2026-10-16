# 원본 저장·조회 API

관리자(`ROLE_ADMIN`)만 사용할 수 있다. Spring Security의 기존 인증과 CSRF 보호를 유지한다. 세션 인증으로 POST를 호출할 때는 유효한 CSRF 토큰도 전달해야 한다.

## 원본 저장

`POST /api/admin/collection/raw-records`

```json
{
  "syncId": 10,
  "externalId": "policy-1",
  "payloadHash": "호출자가 계산한 비교용 해시",
  "payload": { "title": "청년 지원정책" },
  "collectedAt": "2026-10-16T10:01:00+09:00"
}
```

출처와 RUNNING 수집 실행이 DB에 먼저 존재해야 한다. 출처는 `syncId`에서 결정한다. 신규·변경은 스냅샷을 추가하고, 동일 해시는 기존 스냅샷의 마지막 관측 시각만 갱신한다. 두 경우 모두 `200 OK`와 `RawRecordResponse`를 반환한다.

응답 필드는 `rawRecordId`, `source`(출처 코드), `externalId`, `payloadHash`, `collectedAt`(스냅샷 최초 관측 시각), `lastSeenAt`, `payload`다. 원본 JSONB 저장·재조회에는 Hibernate의 Jackson 3 매퍼를 사용한다.

## 원본 조회

`GET /api/admin/collection/raw-records/{rawRecordId}`

`200 OK`와 같은 응답 형식을 반환한다. 서비스 트랜잭션 안에서 출처 코드와 원본을 DTO로 변환하므로 Open EntityManager in View에 의존하지 않는다.

## 오류

| 상태 | 조건 |
| --- | --- |
| 400 | 잘못된 JSON·필수값, 잘못된 ID, 실행 시작 또는 마지막 관측보다 이른 시각 |
| 401 | 인증 없음(JSON 응답을 요청하고 유효한 CSRF 토큰을 전달한 경우) |
| 403 | 관리자 권한 없음 또는 CSRF 검증 실패 |
| 404 | 수집 실행·출처·원본 레코드 없음 |
| 409 | RUNNING 상태가 아닌 수집 실행 |
| 500 | DB 처리 실패. SQL·원본 예외 메시지는 응답에 포함하지 않음 |

애플리케이션에서 처리하는 400·404·409·500 오류는 `ProblemDetail` 형식이다. 인증·CSRF 오류는 Spring Security가 처리한다.

## 검증 및 실행 범위

`RawRecordControllerIntegrationTests`는 MockMvc에서 실제 컨트롤러·보안 필터·서비스·JPA·PostgreSQL을 연결한다. 서비스나 저장소를 목으로 대체하지 않는다. JSONB 저장·재조회, 동일 원본 재수집, A→B→A, 잘못된 요청, 권한·CSRF, 최초 삽입 경합, DB 제약 위반 롤백을 검증한다.

**테스트용으로 분리된 PostgreSQL DB만 지정해야 한다.** 통합 테스트는 `create-drop`과 각 테스트의 전체 데이터 삭제를 사용한다. `YON_TEST_DATABASE_URL`이 없으면 이 통합 테스트는 건너뛴다. 다음 설정으로 기존 `contextLoads`를 포함한 전체 테스트를 실행할 수 있다.

```powershell
$env:YON_TEST_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:55439/yon_controller_test'
$env:YON_TEST_DATABASE_USERNAME = 'yon_test'
$env:YON_TEST_DATABASE_PASSWORD = ''
$env:SPRING_DATASOURCE_URL = $env:YON_TEST_DATABASE_URL
$env:SPRING_DATASOURCE_USERNAME = $env:YON_TEST_DATABASE_USERNAME
$env:SPRING_DATASOURCE_PASSWORD = $env:YON_TEST_DATABASE_PASSWORD
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'create-drop'
$env:SPRING_JPA_OPEN_IN_VIEW = 'false'
# backend 디렉터리에서 JDK 25로 실행
.\gradlew.bat test --rerun-tasks
```

이 원본 저장 API는 원본 저장 HTTP 요청부터 DB 저장·응답까지 제공한다. 외부 API 수집과 원본 저장·실행 집계 연결은 별도의 [수동 최신화 API](manual-sync-api.md)에서 제공한다. 정제 요청 등록은 아직 연결하지 않는다.
