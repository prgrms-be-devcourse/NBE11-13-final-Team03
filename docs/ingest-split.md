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
- 포트: backend 8080, notification-service 8081, data-service 8082.
- B2a 이후 data-service가 떠 있어야 수집·정규화가 돈다 (배포 순서: data-service → backend).
- data-service 정규화 연동(`YON_NORMALIZATION_INGEST_ENABLED`)은 B2a 전에는 backend와 한쪽에서만 켠다.


## 수집 내부 API gRPC 전환 — PR B

- 작업 기준은 #45 브랜치다. #45·#50 및 #39의 선행 작업이 main에 반영되면 PR 기준을 main으로 변경하고 검증한다.
- #50의 `contracts/proto/collection/v1/collection.proto`를 동일한 내용으로 사용한다. 서버 구현은 #50 소유다.
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
운영 compose가 준비되면 DATA_SERVICE_GRPC_ADDRESS=static://data-service:9092를 backend에 전달한다.
이 포트는 내부 네트워크에만 두고, 호스트를 분리할 때 TLS를 적용한다.

배포 순서는 data-service gRPC 서버(#50) → backend 클라이언트(이 단계) → REST 제거(PR C)다.
PR B 배포·실제 수집 확인 전에는 REST를 제거하지 않는다.
현재 분기에는 운영 compose가 없으므로 #49 병합 후 주소 환경변수와 배포 구성을 맞춰야 한다.
