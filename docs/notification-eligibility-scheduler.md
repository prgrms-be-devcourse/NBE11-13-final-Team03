# 신청 가능성 사용자 Fan-out 스케줄러

## 범위·활성화

10단계의 ELIGIBILITY_CHANGED → 저장 사용자 판정·최신 재검증 → 근거·Outbox 경로를 실행 예산 안에서 자동 호출한다.
Notification 전송·수신·푸시는 실행하지 않는다. 실제 환경변수와 기존 기능 플래그는 변경하지 않는다.
실제 첫 버전 자동 기준선은 버전 비교 경로에 연결됐다. WAITING_POLICY 재개·마감 보류 자동 재분석·운영 활성화는 별도 후속이다.
찜 버전 조건과 새 match Outbox 경로의 BECAME_ELIGIBLE 생성 책임은 [notification-match.md](notification-match.md)를 따른다.

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| yon.tracking.eligibility-fanout.enabled | false | 기존 사용자 Worker 등록 옵션 |
| yon.tracking.eligibility-outbox.enabled | false | 기존 Outbox 경로 등록 옵션 |
| yon.tracking.eligibility-fanout.scheduler.enabled | false | 자동 호출 추가 동의 |
| app.scheduling.enabled | true | 공통 자동 실행 제어 |
| yon.tracking.eligibility-fanout.scheduler.max-pages-per-run | 10 | 한 호출 전체 페이지 예산, 1~500 |
| yon.tracking.eligibility-fanout.scheduler.max-pages-per-event | 2 | 이벤트 하나의 페이지 예산, 1~전체 예산 |
| yon.tracking.eligibility-fanout.scheduler.poll-interval-ms | 60000 | fixed-delay 간격과 정상 반납 후 최소 대기, 최소 1000ms |
| yon.tracking.eligibility-fanout.batch-size | 25 | 기존 페이지 사용자 수, 1~500 |
| yon.tracking.eligibility-fanout.max-events-per-run | 10 | 기존 후보 조회 상한 |

Worker는 기존 두 기능 옵션이 모두 true여야 등록된다. 자동 실행은 추가 scheduler 옵션과 공통 제어도 모두 true여야 한다.
기존 두 기능 옵션만 켠 환경이 이번 변경으로 자동 실행되는 것을 방지한다. 버전 비교·분석 스케줄러의 옵션과는 독립적이다.
공통 제어 또는 scheduler 옵션이 꺼져 있어도 등록된 Worker를 명시적으로 호출할 수 있다.

## 예산·커밋·다음 실행

- 수동/자동 모두 같은 runOnce 예산을 적용한다. 기존처럼 한 이벤트의 모든 사용자를 한 호출에서 끝낼 것을 보장하지 않는다.
- 후보 조회는 기존 타입 필터·nextRetryAt/id 순서·max-events-per-run을 사용한다. claim 성공 후에만 페이지 처리를 시작한다.
- 빈 마지막 페이지·만료 종료·실패 호출도 전체/이벤트별 페이지 예산에 포함한다. claim 실패는 페이지 예산을 쓰지 않는다.
- 이벤트별 예산 소진 뒤에는 실행권을 반납하고 뒤 후보로 넘어간다. 전체 예산이 소진되면 추가 이벤트를 claim하지 않는다.
- 기본값에서는 최대 10번의 페이지 호출, 최대 250명분을 방문한다. 마지막 빈 페이지 등으로 실제 방문 수는 더 작을 수 있다.
  페이지 수는 시간 제한이 아니다. 느린 판정/DB 잠금 대기로 한 페이지가 오래 걸리는 경우까지 실행 시간 상한을 보장하지 않는다.
- 근거·Outbox·사용자 커서·heartbeat는 기존 페이지 트랜잭션에서 원자적으로 커밋한다. 반납은 그 뒤 별도 트랜잭션이다.
- 정상 예산 반납은 PENDING으로 돌리고 lease/heartbeat를 지우되 사용자 커서와 근거·Outbox·원래 발생 시각은 보존한다.
  processedAt은 설정하지 않는다. 완료나 WAITING_POLICY로 간주하지 않는다.
- claim 쿼리는 기존대로 retryCount를 1 올린다. 정상 반납은 **해당 claim의 1만 돌려주며** 이전 장애 횟수는 지우지 않는다.
  여러 번 정상 분할 실행한 이벤트가 maxAttempts 때문에 FAILED 되는 것을 방지한다. 최종 완료 claim의 카운트는 기존 방식을 유지한다.
- 반납 시 nextRetryAt을 현재 시각 + poll 간격으로 설정한다. 즉시 재claim하지 않으며, 이미 대기 중인 다른 due 이벤트에 처리 기회를 준다.
  지속적인 유입까지 포함한 엄격한 공정성/처리 지연 상한을 보장하는 것은 아니다.
- 다음 실행에서 새 lease로 claim하고 DB의 마지막 사용자 ID 다음부터 재개한다. 재시작해도 JVM 커서에 의존하지 않는다.

## 장애·소유권·보류

- 실제 페이지 실패는 기존 retryDelay/maxAttempts를 적용한다. 실패 전 페이지의 커밋은 유지하고 실패 페이지는 롤백한다.
- 반납 실패도 장애 경로로 처리한다. retry 저장까지 실패하면 lease는 기존 만료 복구 대상에 남고 뒤 후보 처리는 계속한다.
- 반납 전 프로세스가 중단되면 기존 stale lease 복구를 사용한다. 이 경우는 정상 반납이 아니므로 claim 횟수를 환급하지 않는다.
- 실행권 반납과 retry는 이벤트 행 잠금 안에서 타입과 lease token을 확인한다. 과거 실행자가 새 실행자의 lease를 풀거나 횟수를 환급할 수 없다.
- 같은 JVM의 중복 Worker 호출은 실행 가드로 건너뛴다. 여러 인스턴스는 기존 원자적 claim·행 잠금·UNIQUE·멱등키에 의존한다.
- 정상 완료·만료·WAITING_POLICY 전환은 기존 페이지 로직을 유지한다. 보류가 남은 마지막 빈 페이지는 WAITING_POLICY로 전환하고 자동 후보에서 제외한다.
- Run.pageCalls는 처리 호출 수이며 성공 사용자·완료 이벤트·알림 전달 수가 아니다. scheduler 예외는 잡고 다음 주기에 재호출한다.

## 검증·활성화 전 확인

- EligibilityFanoutWorkerTest: 전체/이벤트별 예산, 뒤 후보 진행, claim 실패, 실패 격리·반납 실패·중복 호출·실행 가드 복구.
- EligibilityFanoutSchedulingTest: 기본 비활성·각 게이트·공통 제어·설정 검증·poll 실패 후 재호출. 실제 타이머는 실행하지 않는다.
- EligibilityNotificationRevalidationPostgresTest: 여러 번 반납 후 완료/멱등성, 이전 장애 횟수 보존, 과거 lease 거부,
  다른 due 이벤트 진행, 페이지 실패 후 커밋된 커서·근거·Outbox로 재개. 기존 연결·정책 보류 테스트도 유지한다.

### 로컬 검증 결과 (2026-10-25)

| 테스트 | 건수 | 결과 |
| --- | --- | --- |
| EligibilityFanoutWorkerTest | 7 | skip/failure/error 0 |
| EligibilityFanoutSchedulingTest | 7 | skip/failure/error 0 |
| EligibilityNotificationRevalidationPostgresTest | 39 | skip/failure/error 0 |
| IngestBoundaryTest | 3 | skip/failure/error 0 |
| **합계** | **56** | **skip/failure/error 0** |

- `./gradlew test bootJar --rerun-tasks` 성공. 전체 실행의 비정상 카운트 검색 결과는 기존 조건부 테스트 98개 건너뜀이며 실패·오류는 0이다.
- 관련 조건부 DB 테스트는 테스트 전용 `yon_test`에서 별도 실행했다. EligibilityResultServiceTest 18개와 ConditionSetBackfillTest 1개 모두 skip/failure/error 0이다. 나머지 조건부 테스트의 실행을 뜻하지 않는다.
- PR 리뷰 검증 보고 기준 backend 834개 실패 0·조건부 건너뜀 98이다. Worker 7개·Scheduling 7개·추가 PostgreSQL 5개가 위 표에 포함된다.
- 실제 타이머 주기 실행·장시간 실행·다중 인스턴스 주기 실행·업무 DB·AWS/FCM은 검증하지 않았다. 최신 커밋의 CI 성공과 운영 활성화는 별도 확인 대상이다.

### 운영 관측·수동 복구 판단

- ELIGIBILITY_CHANGED 중 `processingStatus=FAILED`이면서 `lastUserId > 0`인 이벤트를 확인 대상으로 둔다.
  정상 페이지 커밋 뒤 반납 전에 중단되면 claim 횟수가 환급되지 않으므로, 한도에 도달한 이벤트는 진행을 보존한 채 FAILED가 될 수 있다.
  이 조건이 반납 전 중단을 확정하는 것은 아니며, 이후 페이지의 실제 실패로도 같은 상태가 생길 수 있다.
- 실패 원인·누적 횟수·마지막 사용자 커서·기존 근거/Outbox·원래 발생 시각과 7일 만료를 함께 확인한 뒤 수동 재개 필요성을 판단한다.
  관측 조건만으로 FAILED를 자동 PENDING 전환하거나 커서를 초기화하지 않는다. 수동 재개 도구·절차는 이번에 추가하지 않았다.
- retryCount와 lastError는 영구 장애 이력의 쌍이 아니다. 기존 claim과 정상 반납에서 lastError를 지우므로 횟수가 남아도 오류 정보가 없을 수 있다.
  과거 오류 보존이 필요하면 별도 장애 이력을 후속 설계한다. 이번 변경은 오류 저장 동작을 바꾸지 않는다.

운영 활성화 전에는 첫 버전/누락 이력 처리·기존 찜 null 처리·잠정 상태 매핑·UPCOMING·보류 재개 정책과 DB 전환을 확인한다.
신규 match 경로의 관측 비용 P2·추천 링크·배포 전제는 notification-match.md를 따른다.
25명 판정 페이지의 지연·부하, 스케줄러 풀 점유, 이벤트 적체·7일 만료 내 처리 가능 여부, 반복 장애 감시를 검증한다.
코드 연결이나 테스트 성공만으로 실제 전달·푸시 활성화를 승인하지 않는다.
