# 수집·정규화 서비스 분리 (DataService)

## 결정
- 서버를 셋으로 나눈다: **DataService**(수집·정규화), **backend**(판정·검색·추천·회원 등), **notification-service**(알림).
- 같은 저장소 안 독립 Gradle 프로젝트. 서비스 사이 명령은 Feign, 조회는 공유 DB.
- DB는 당분간 공유한다 (단순화). 서비스별 DB 분리는 나중에.

## 경계 규칙
- DataService가 공고·원본·조건 묶음·지역 테이블을 쓰고, backend는 읽기만 한다.
- backend에는 판정·검색이 쓰는 **읽기 어댑터**(공고 엔티티·조회 리포지토리·조회 DTO·신청 상태 계산)와
  수집 관리자 API 전달(`collection.controller`·`exception`, Feign `client.DataServiceClient`)만 남긴다.
  - 수집·정규화 패키지(opportunity.ingest·normalization·service·conditionset, region.client·service, 수집 서비스)는 backend에 두지 않는다 (`IngestBoundaryTest`, AGENTS.md).
  - 조건 묶음은 data-service만 만든다. backend는 읽기만 한다.
- backend 테스트의 공고 데이터는 정규화 서비스 대신 `OpportunityFixtures`(test)로 만든다. 조건 행 모양은 data-service 정규화 결과와 같게 유지한다.
- 서비스 사이에 메모리 이벤트를 쓰지 않는다. 판정 배치는 공유 DB에서 바뀐 조건 묶음을 찾는다.
- 엔티티가 두 서비스에 따로 있으므로, 공고 엔티티를 바꿀 때는 backend와 data-service를 같이 맞춘다 (`ddl-auto: update`는 개발용, 운영 배포 때 변경 예정).

## 진행 단계
| 단계 | 내용 |
|---|---|
| 리팩터 | 판정·검색 → 정규화 의존 끊기 (신청 상태 계산 공용화, 정규화 DTO 분리, 판정 배치의 백필 호출 제거), 경계 테스트 |
| A | data-service에 수집·정규화 복사, 독립 실행 설정 |
| B1 | data-service 내부 API(`/internal/collection/**`) + 내부 키(`X-INTERNAL-KEY`) |
| B2a | backend 실행 경로에서 수집·정규화 제거, 수집 관리자 API를 Feign으로 DataService에 전달 |
| B2b | backend에 남은 정규화 서비스·규칙·조건 묶음 생성 제거, 판정·검색 테스트 데이터를 `OpportunityFixtures`로 준비 |

## 운영 메모
- 포트: backend 8080, notification-service 8081, data-service 8082.
- B2a 이후 data-service가 떠 있어야 수집·정규화가 돈다 (배포 순서: data-service → backend).
- data-service 정규화 연동(`YON_NORMALIZATION_INGEST_ENABLED`)은 B2a 전에는 backend와 한쪽에서만 켠다.
- B2b 이후 정규화 규칙·코드표(youth-policy-codes.yaml, ncs-codes.tsv)는 data-service에만 있다.
