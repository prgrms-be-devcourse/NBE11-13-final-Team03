# 수집·정규화 서비스 분리 (DataService)

## 결정
- 서버를 셋으로 나눈다: **DataService**(수집·정규화), **backend**(판정·검색·추천·회원 등), **notification-service**(알림).
- 같은 저장소 안 독립 Gradle 프로젝트. 수집 관리 명령·조회는 gRPC, 공고·판정 조회는 공유 DB. 알림 HTTP Publisher의 Feign은 별도 경로다.
- DB는 당분간 공유한다 (단순화). 서비스별 DB 분리는 나중에.

## 경계 규칙
- DataService가 공고·원본·조건 묶음·지역 테이블을 쓰고, backend는 읽기만 한다.
- backend의 메인·공용 패키지는 수집·정규화 패키지를 import하지 않는다 (`IngestBoundaryTest`, AGENTS.md).
  - 메인: client, collection(관리자 API·DTO·오류 코드), auth, eligibility, search, interest, preference, user, config, global
  - 공용: opportunity.domain, opportunity.dto, region.directory, region.domain
  - 수집·정규화: opportunity.ingest·normalization·service·conditionset, region.client·service
- backend.collection에는 수집 실행 코드를 두지 않는다. client와 collection도 정규화 패키지를 import하지 않는다.
- backend에는 판정·검색이 쓰는 **읽기 어댑터**(공고 엔티티·조회 리포지토리·조회 DTO·신청 상태 계산)만 남긴다.
- 서비스 사이에 메모리 이벤트를 쓰지 않는다. 판정 배치는 공유 DB에서 바뀐 조건 묶음을 찾는다.
- 엔티티가 두 서비스에 따로 있으므로, 공고 엔티티를 바꿀 때는 backend와 data-service를 같이 맞춘다 (`ddl-auto: update`는 개발용, 운영 배포 때 변경 예정).

## 진행 단계
| 단계 | 내용 |
|---|---|
| 리팩터 | 판정·검색 → 정규화 의존 끊기 (신청 상태 계산 공용화, 정규화 DTO 분리, 판정 배치의 백필 호출 제거), 경계 테스트 |
| A | data-service에 수집·정규화 복사, 독립 실행 설정 |
| B1 | data-service 내부 API(`/internal/collection/**`) + 내부 키(`X-INTERNAL-KEY`) |
| B2a | backend 실행 경로에서 수집·정규화 제거, 수집 관리자 API를 Feign으로 DataService에 전달 |
| B2b | backend에 남은 정규화 서비스 제거, 판정·검색 테스트 데이터 준비 방식 변경 |

## 운영 메모
- 포트: backend 8080, notification-service 8081, data-service gRPC 9092.
- B2a 이후 data-service가 떠 있어야 수집·정규화가 돈다 (배포 순서: data-service → backend).
- data-service 정규화 연동(`YON_NORMALIZATION_INGEST_ENABLED`)은 B2a 전에는 backend와 한쪽에서만 켠다.


## 수집 내부 API gRPC 전환 — PR B

- 최신 main으로 rebase했다. 서버 PR #53을 먼저 배포하고 backend를 전환한다.
- #53의 `contracts/proto/collection/v1/collection.proto`를 동일한 내용으로 사용한다. 서버 구현은 #53 소유다.
- backend의 `/api/admin/collection/**` 5개 경로와 ADMIN 권한, HTTP 응답 DTO는 유지하고 내부 Feign 호출만 gRPC로 바꾼다.
- `totalCount`는 항상 null, `payloadHash`는 요청 호환용으로 받되 서버가 계산한다.
- 목록의 recordFailures는 빈 배열이며, 실패 건수는 유지된다. 단건 조회는 상세를 포함한다.
- proto의 nullable 표현: 없는 recordResultId(0)·resultType(빈 문자열)은 HTTP DTO에서 null로 복원한다.
- StartSync deadline 3초, 나머지 4개 호출 10초. 내부 키는 collection stub에만 붙인다.
- 오류는 trailer의 COLLECTION_001~006을 우선 복원하고, 연결·deadline·내부 인증 오류는 503 COLLECTION_008로 변환한다.
- 잘못된 경로·쿼리 값은 400 COLLECTION_003, 파싱 불가능한 요청 JSON은 400 COLLECTION_002다.
- backend 수신 한도는 16MB. 생성 코드는 build/에만 둔다.

Boot 4.1.1에 맞춰 공식 `spring-boot-starter-grpc-client`를 사용한다.
Spring gRPC 1.1.1 / protobuf·protoc 4.35.1 / grpc-java·코드 생성기 1.83.1 / protobuf Gradle 플러그인 0.9.6으로 PR A와 맞춘다.
[공식 Boot gRPC 문서](https://docs.spring.io/spring-boot/4.1/reference/io/grpc.html)

```yaml
spring:
  grpc:
    client:
      channel:
        data-service:
          target: ${DATA_SERVICE_GRPC_ADDRESS:static://localhost:9092}
          ssl:
            enabled: false
          inbound:
            message:
              max-size: 16MB
```

`yon.data-service.internal-api-key`는 기존 DATA_SERVICE_INTERNAL_API_KEY를 사용한다.
DATA_SERVICE_BASE_URL 및 data-service용 Feign timeout 설정은 제거한다.
OpenFeign·Spring Cloud BOM·EnableFeignClients는 #39의 알림 호출을 위해 유지한다.
로컬 compose는 DATA_SERVICE_GRPC_ADDRESS=static://data-service:9092를 주입한다.
운영은 분리된 데이터 서버의 사설 IP(static://<데이터 서버 사설 IP>:9092)를 사용한다.
평문은 기존 사설망·보안 그룹·내부 키 전제로 유지하며 TLS 필수 여부는 팀 논의가 필요한 미결정 사항이다.

배포 순서는 data-service gRPC 서버(#53) → backend 클라이언트(이 단계) → REST 제거(PR C)다.
기존 REST backend가 동작 중인 환경에서는 REST 공존 data-service → gRPC backend → gRPC 전용 data-service 순으로 전환한다.
운영 배포 구성은 #56의 deploy/main·deploy/data를 따른다. PR C에서 REST 8082 점검과 매핑을 제거한다.

## 수집 내부 API gRPC 전환 — PR C

PR B(#54)의 backend 전환을 반영하고 REST 수신부를 제거했다.
data-service는 gRPC 9092만 제공하며 RestClient·Jackson을 통한 외부 REST 호출은 유지한다.
내부 키 속성은 yon.data-service.internal-api-key, 환경변수는 DATA_SERVICE_INTERNAL_API_KEY다.
운영·로컬 compose의 포트·healthcheck와 deploy.yml 기동 점검도 9092 기준이다.
배포 점검은 인증된 읽기 RPC로 키와 DB 조회를 확인하며 복구·백필 완료는 별도로 확인한다.
이전 REST backend로 롤백할 때는 data-service도 REST 공존 버전으로 함께 되돌려야 한다.
코드 병합과 운영 배포를 구분한다. 운영 환경 준비 후 data-service → backend 순서로 배포하고
실제 관리자 API로 사설망 연결·수집을 검증한다. 기존 REST 환경의 전환 순서는 PR B 절을 따른다.
