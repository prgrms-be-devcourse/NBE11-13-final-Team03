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
- 환경변수: `DB_URL`, `APPLICATION_DB_PASSWORD`(메인과 같은 DB·계정, 아래 DB 경계), `DATA_SERVICE_INTERNAL_API_KEY`, `EMPLOYMENT24_API_KEY`, `YOUTH_POLICY_API_KEY`, `REGION_CODE_API_KEY`, 실행 플래그 4개. 예시는 `.env.example`.

### 전환 순서 (메인 서비스에서 수집·정규화를 옮기는 동안)

| 순서 | 할 일 | 수집·정규화를 실행하는 쪽 |
|---|---|---|
| 0 | 지금 (data-service 미배포) | 메인 |
| 1 | data-service 배포, **메인의 실행 플래그 4개를 모두 끔** (아래 실행 플래그 표) | data-service |
| 2 | 메인 관리자 API를 data-service 프록시로 교체 (`DATA_SERVICE_BASE_URL`, `DATA_SERVICE_INTERNAL_API_KEY`) | data-service |
| 3 | 메인에서 수집·정규화 코드 삭제 | data-service |

- 1단계 전에 data-service를 띄워 보려면 data-service 쪽 실행 플래그를 모두 `false`로 둡니다 (두 서비스가 같은 스케줄·대조·백필을 중복 실행하지 않게).
- 2·3단계는 메인 서비스 PR(수집 관리자 API 전달, 정규화 제거)로 진행합니다.

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


## 수집 gRPC 내부 API (전환 PR A)

REST `/internal/collection/**`와 gRPC `yon.collection.v1.CollectionService`가 같은 서비스 빈을 호출한다.
backend 전환과 배포가 끝난 뒤 별도 PR에서 REST를 제거한다.

- 계약: `contracts/proto/collection/v1/collection.proto` (필드 번호 재사용 금지, 삭제 시 `reserved`)
- RPC: `StartSync`, `ListSyncJobsByDate`, `GetSyncJob`, `SaveRawRecord`, `GetRawRecord`
- 포트: `DATA_SERVICE_GRPC_PORT` (기본 9092), metadata `x-internal-key`: `DATA_SERVICE_INTERNAL_API_KEY`
- 수신 상한: `spring.grpc.server.inbound.message.max-size=16MB`
- `StartSync`의 페이지 크기/최대 페이지 수 0은 기본값 100/1000이다.
- 날짜는 한국 시간 접수일(YYYY-MM-DD), 시각은 UTC Timestamp이다.
- 목록은 `record_failures`를 제외하고 실패 건수를 유지한다. 단건 조회는 상세 전체를 포함한다.
- JSON은 문자열로 전달하여 기존 원본 해시 계산을 유지한다.
- 오류는 gRPC status와 trailer `error-code`로 전달한다. 예외 원문은 노출하지 않는다.
- health/reflection 예외는 두지 않으며 모든 등록 RPC에 키가 필요하다.

Boot 4.1.1부터 gRPC 자동 설정은 Boot에 통합되어 공식
`org.springframework.boot:spring-boot-starter-grpc-server`를 사용한다.
Boot BOM 기준 Spring gRPC 1.1.1 / protobuf 4.35.1 / grpc-java 1.83.1,
protobuf Gradle 플러그인 0.9.6을 사용하고 생성기는 런타임과 같은 버전으로 고정한다.
설정 키도 Boot 4.1의 `inbound.message.max-size`를 사용한다.
[공식 Boot gRPC 문서](https://docs.spring.io/spring-boot/4.1/reference/io/grpc.html)

backend의 gRPC 주소 환경변수 예시는 준비만 추가하며 실제 호출 전환은 PR B(#54)에서 한다.
EC2 3대 구성(#56)에서는 backend와 data-service가 별도 서버이므로,
backend의 주소는 `DATA_SERVICE_GRPC_ADDRESS=static://<데이터 서버 사설 IP>:9092`로 둔다.
서버 간에는 compose 서비스명 `data-service`가 해석되지 않으므로 운영 주소에 사용하지 않는다.
로컬 실행만 `static://localhost:9092`를 쓴다.
환경변수 예시는 `deploy/main/.env.example`, `deploy/data/.env.example`에 있다.

### 내부 네트워크와 평문 통신

현재 배포는 신뢰하는 사설 VPC 내부의 서버 간 통신으로 한정하고,
데이터 서버의 사설 IP에 포트를 바인딩하며 보안 그룹에서 TCP 9092의 송신원을
메인 서버 보안 그룹(`yon-main`)으로 제한하는 전제로 평문 gRPC와 전용 내부 키를 사용한다.
공개 IP·전체 인터넷에 9092를 허용하지 않으며, `DATA_SERVICE_BIND_IP`에 데이터 서버 사설 IP를 넣는다.
내부 키는 호출 인증용이고 전송 암호화를 제공하지 않는다.
이 접근 제한을 유지할 수 없는 네트워크나 전송 암호화가 필요한 환경에서는 서버·클라이언트에 TLS를 설정한다.

이 PR A는 REST 8082와 gRPC 9092를 공존시킨다.
현재 운영 compose·Dockerfile·기동 확인은 아직 8082 기준이며, 9092 포트 매핑·Dockerfile EXPOSE·
배포 기동 확인과 보안 그룹 안내는 배포 담당자의 별도 PR에서 맞춘다.
PR C(#55)의 REST 제거와 함께 반영해야 하며, 그 전에 HTTP 8082 규칙을 제거하지 않는다.

### 포트 바인딩과 기동 완료

Spring gRPC 1.1.1의 `GrpcServerLifecycle`은 `SmartLifecycle`이다.
일반 singleton 빈 초기화 뒤, 컨텍스트 refresh의 마지막 lifecycle 시작 단계에서 Netty 서버를 바인딩한다.
`ContextRefreshedEvent`와 `ApplicationRunner`·`ApplicationReadyEvent`보다 먼저 포트를 열 수 있으므로,
**9092 TCP 연결 성공은 listener 생존 확인이며 전체 기동 완료나 업무 준비 완료를 보장하지 않는다.**
DB 경계 검사는 DataSource 초기화 중 수행되지만, 기동 후 지역 코드 동기화(`RegionSyncRunner`),
중단된 수집 복구(`SyncJobService.recoverInterrupted`), 조건 묶음 백필은 포트가 열린 뒤에도 실행될 수 있다.
배포의 TCP 점검을 이 범위의 생존 확인으로 사용하고,
업무 확인은 기동 작업 종료를 확인한 후 인증된 `ListSyncJobsByDate`를 유효한 한국 날짜로 호출해
내부 키와 DB 조회를 검증한다. 목록이 비어 있는 정상 응답도 성공이다.
조회 성공은 복구·백필 완료 자체를 증명하지 않으므로 해당 작업 완료 여부는 별도로 확인한다.
`StartSync`는 수집을 생성하는 명령이므로 단순 기동 점검에는 사용하지 않는다.

확인 근거:

- [Spring gRPC 1.1.1 GrpcServerLifecycle](https://github.com/spring-projects/spring-grpc/blob/v1.1.1/spring-grpc-core/src/main/java/org/springframework/grpc/server/lifecycle/GrpcServerLifecycle.java)
- [Spring 컨텍스트 refresh 순서](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java)
- [Boot 애플리케이션 이벤트 순서](https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.application-events-and-listeners)

배포 순서는 data-service(서버 추가) → backend(클라이언트 전환) → data-service(REST 제거)다.
현재 backend-image 워크플로는 모든 main push에서 실행하므로 proto 변경도 이미 포함한다.
data-service 및 backend PostgreSQL PR 워크플로는 `contracts/proto/**`도 검사한다.
