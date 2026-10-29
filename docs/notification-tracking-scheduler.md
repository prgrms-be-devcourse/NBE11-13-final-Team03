# 버전 비교·분석 스케줄러

## 범위와 활성화 경계

10단계의 버전 비교 → 분석만 연결한다. 사용자별 순회·Publisher·Notification Consumer·푸시는 실행하지 않는다.
실제 첫 버전(versionNo=1, 이전 버전 없음)은 자동 기준선으로 기록한다. 초기 적재 여부 추론,
WAITING_POLICY 재개와 푸시 전달은 수행하지 않는다. 최신 기준선·적합 알림 계약은 [notification-match.md](notification-match.md)를 따른다.

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| yon.tracking.version.enabled | false | 버전 비교 파이프라인 등록 |
| yon.tracking.version-analysis.enabled | false | 기존 분석 경로 등록. 위 옵션과 함께 필요 |
| yon.tracking.version.max-policies-per-run | 25 | 한 번에 방문할 정책 수, 1~500 |
| yon.tracking.version.poll-interval-ms | 60000 | fixed-delay 간격, 최소 1000ms |
| app.scheduling.enabled | true | 공통 스케줄 제어. false이면 타이머 미등록 |

두 기능 옵션이 모두 true여야 VersionTrackingPipeline을 등록한다. 공통 스케줄 제어까지 켜져야 VersionTrackingScheduler를 등록한다.
공통 제어가 false이면 파이프라인을 명시적으로 호출할 수는 있어도 자동 실행하지 않는다.
이번 변경은 실제 .env·DB·기존 날짜/사용자 순회·전달/푸시 플래그를 바꾸지 않는다.
코드 연결과 운영 활성화 승인은 별개다. 기본 비활성 상태를 유지한다.

## 실행·커밋·복구

- 한 poll은 정책 ID 키셋 페이지 하나를 처리하고, 정책마다 가장 이른 미완료 버전 한 개를 비교한다.
- COMPARED 또는 WAITING_FOR_EVENT_ANALYSIS인 버전을 분석 경로에 전달한다. NO_CHANGE 등 이미 끝난 분석은 기존 서비스가 NOT_READY로 반환할 수 있다.
- 실제 첫 버전은 BASELINE_RECORDED로 기록하고 분석을 호출하지 않는다. 첫 조회 버전이 2 이상인데 이전 이력이 없으면
  WAITING_FOR_BASELINE으로 대기한다. 누락 이력을 임의 기준선으로 바꾸지 않는다.
- 다음 poll은 이전 페이지 마지막 정책 ID부터 이어가고, 페이지 끝에서 커서를 0으로 돌려 새 순회를 시작한다.
  기준선 대기·실패 정책이 앞쪽에 있어도 뒤 정책을 방문한다. 전체 한 순회 지연은 정책 수·예산·poll 간격과 처리 시간에 좌우된다.
- 커서는 JVM 메모리의 순회 힌트다. 재시작하면 0부터 다시 읽으며 DB의 버전 기록·이벤트 UNIQUE가 처리 근거/멱등성을 보장한다.
- 파이프라인 전체를 하나의 트랜잭션으로 묶지 않는다. 비교와 분석은 각각 커밋한다.
  분석 저장 실패 시 비교 기록/변경 이력은 남고 해당 분석 트랜잭션만 롤백한다. 다음 순회에서 READY 버전을 다시 분석한다.
- PROCESSED_WITH_DEFERRED_DEADLINE 상태는 순차 진행을 위한 processedAt이 있으므로 compareNext의 자동 비교 후보에서 제외된다.
  분석 규칙 버전을 올려도 스케줄러가 해당 보류 버전을 자동 재분석하지 않는다. 현재는 명시적 분석 호출이 필요하며,
  규칙 변경에 따른 보류 버전 재스캔은 후속으로 다룬다. 이는 실패한 READY 버전의 자동 재시도와 별개다.
- 한 정책의 분석 실패가 나머지 정책을 중단하지 않는다. 정책 목록 조회 실패는 커서를 유지하고 다음 poll에서 재시도한다.
  별도 실패 Backoff/최대 횟수는 이번에 추가하지 않았다. 반복 장애는 경고와 메시지 없는 DEBUG 원인/스택 진단으로 확인한다.
- 동일 JVM의 중복 호출은 실행 가드로 건너뛴다. 여러 인스턴스 간 정합성은 기존 정책 행 잠금과 UNIQUE/멱등키에 의존한다.
- Run의 analysisCalls는 분석 호출 수이며, 완료·알림 생성 건수가 아니다. 이미 처리됨·사용 불가 등의 반환도 호출 수에 포함된다.

생성된 ELIGIBILITY_CHANGED는 PENDING으로 남는다. 이 파이프라인이 사용자 Worker를 직접 호출하지 않는다.
사용자 Worker의 예산·자동 실행은 별도 기본 비활성 경로로 연결하며 [신청 가능성 사용자 Fan-out 스케줄러](notification-eligibility-scheduler.md)를 따른다.
기존 사용자 Worker의 lease 복구와 WAITING_POLICY 보류 계약은 변경하지 않는다.

## 활성화 전 조건

기존 문서 notification-version-tracking.md의 DB 제약 전환·첫 버전/이력 누락 처리·팀 미확정 정책·7일 만료 조건을 먼저 확인한다.
운영 규모에서 한 순회가 만료 내에 끝나는지, 반복 장애와 정책별 지연을 어떻게 감시할지 검증해야 한다.
DB에 초기 데이터가 있다는 사실만으로 기준선이 설정됐다고 간주하지 않는다.

## 검증 범위

VersionTrackingPipelineTest는 키셋 진행/초기화, 대기 정책 통과, 정책별 분석 실패 격리, 조회 실패 커서 보존, 중복 실행 방지를 검사한다.
VersionTrackingSchedulingTest는 기본 비활성, 단일 옵션 차단, 공통 제어, 설정 검증, poll 실패 뒤 재호출을 검사한다.
EligibilityNotificationRevalidationPostgresTest는 실제 비교 → 분석 → 명시적 사용자 순회 → Outbox의 연결·멱등성과
실제 첫 버전 자동 기준선과 분석 생략·누락 이력 대기, 분석 실패 시 비교 커밋 보존/READY 재처리를 검사한다.

### PR #71 검증 이력 (2026-10-25)

아래 건수는 PR #71 당시 결과다. 첫 버전 자동 기준선을 추가한 PR #80의 전체 회귀 결과는 notification-match.md를 따른다.

| 테스트 | 건수 | 결과 |
| --- | --- | --- |
| VersionTrackingPipelineTest | 5 | skip/failure/error 0 |
| VersionTrackingSchedulingTest | 7 | skip/failure/error 0 |
| EligibilityNotificationRevalidationPostgresTest | 34 | skip/failure/error 0 |
| IngestBoundaryTest | 3 | skip/failure/error 0 |
| **합계** | **49** | **skip/failure/error 0** |

- `./gradlew test bootJar --rerun-tasks` 성공. 전체 테스트의 비정상 카운트 검색 결과는 기존 조건부 테스트 98개 건너뜀이며 실패·오류는 0이다.
- 관련 조건부 DB 테스트는 테스트 전용 `yon_test`에서 별도 실행했다. EligibilityResultServiceTest 18개와 ConditionSetBackfillTest 1개 모두 skip/failure/error 0이다. 나머지 조건부 테스트의 실행을 뜻하지 않는다.
- 설정 테스트는 빈 등록·옵션 조합과 명시적 호출을 검사한다. PostgreSQL 연결 테스트도 공통 스케줄 제어를 끈 상태에서 파이프라인을 직접 호출한다.
- 실제 타이머 주기 실행·장시간 실행·다중 인스턴스 스케줄 실행·업무 DB·운영 배포·외부 전달/FCM 검증은 수행하지 않았다. 현재 브랜치의 CI·병합은 별도 확인 대상이다.
