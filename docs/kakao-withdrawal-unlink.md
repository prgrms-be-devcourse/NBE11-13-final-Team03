# 회원 탈퇴와 카카오 앱 연결 해제

## 책임과 흐름

기존 `DELETE /api/users/me` 계약은 유지한다. backend가 연결 해제를 수행하며 프론트·notification-service에
카카오 어드민 키나 카카오 토큰을 전달하지 않는다. 카카오 **계정 삭제**나 일반 로그아웃이 아니라 우리 앱과의 연결 해제다.

1. backend 탈퇴 트랜잭션이 카카오 식별자 mutex → 사용자 행 순으로 잠근다.
2. `kakao_unlink_tasks`에 작업을 남기고 기존 미발행 알림 취소·`USER_WITHDRAWN` Outbox 저장·회원 데이터 삭제를 수행한다.
3. 모두 같은 트랜잭션으로 커밋/롤백한다. 이 트랜잭션은 외부 카카오 API를 호출하지 않는다.
4. Worker가 커밋된 `PENDING` 작업을 원자적으로 `PROCESSING`으로 바꾸고 시도 횟수를 증가시킨다.
5. DB 트랜잭션 종료 후 `POST https://kapi.kakao.com/v1/user/unlink`를 호출한다.
6. 200 응답의 카카오 회원번호가 요청 대상과 같을 때만 별도 트랜잭션으로 `COMPLETED`를 기록한다.

카카오 장애가 이미 커밋한 서비스 탈퇴를 되돌리지는 않는다. 기존 Notification 탈퇴 이벤트의 발행·소비는 그대로 유지한다.
Worker는 기본 5초 폴링이므로 탈퇴 응답 직후 연결 해제가 이미 끝났다고 보장하지 않는다.
새 기능은 배포 전 과거에 탈퇴한 사용자를 자동 복구하거나 역으로 찾아 연결 해제하지 않는다.

## 재시도와 결과 불명

| 결과 | 처리 |
| --- | --- |
| 200 + 대상 ID 일치 | `COMPLETED` |
| HTTP 429·5xx | 기본 최대 5회, 기준 1분의 지수 Backoff (배수 상한 64) 후 `FAILED` |
| 그 밖의 HTTP 상태·응답 불일치·파싱 실패 | `FAILED`, 자동 성공 처리하지 않음 |
| 타임아웃·네트워크 오류·인터럽트 | `FAILED / KAKAO_OUTCOME_UNKNOWN`, 운영자 확인 |
| 외부 호출 중 서버 중단·완료 결과 DB 저장 실패 | `PROCESSING` 유지, 운영자 확인 |

네트워크 오류는 카카오가 실제 연결 해제를 완료했는지 모호하다. 무조건 재시도하거나 lease 만료로
`PROCESSING`을 자동 회수하면 늦은 이전 요청이 새 연결을 끊을 수 있으므로 자동 회수하지 않는다.
작업 선점 전 `PENDING`은 재시작 후 자동 처리되지만, 선점 후 결과 불명 작업까지 자동 복구된다고 설명하지 않는다.
외부 API와 DB 간 exactly-once/분산 원자성은 보장하지 않는다.

`attempt_count`는 Worker의 실제 요청 예정 시도 수이며, 선점 커밋 직후 프로세스가 죽으면 외부 호출 전이어도 증가할 수 있다.
메모리 큐가 아닌 DB 작업이므로 서버 재시작으로 사라지지 않는다. 로그·오류 열에는 응답 원문·토큰·어드민 키를 넣지 않는다.

## 재가입 게이트·동시성

- 로그인과 탈퇴는 동일한 카카오 식별자 advisory mutex를 사용한다. 로그인 확인·회원 저장은 같은 쓰기 트랜잭션이다.
- `PENDING`·`PROCESSING`·`FAILED`는 모두 재가입을 차단한다. 실패했다고 자동으로 허용하지 않는다.
- `COMPLETED` 후 새 카카오 로그인으로 다시 가입할 수 있다. 새 연결 과정은 카카오 로그인에서 처리한다.
- 연결 해제 전에 시작한 사용자 정보 조회가 뒤늦게 도착한 경우도 거부한다. 완료 기록보다 나중에 새로 시작한 로그인만 허용한다.
- 처리 중 기존 실패 핸들러가 `/login-failure.html`로 보낸다. OAuth 오류 코드는 `kakao_unlink_pending`이다.
  현재 프론트는 일반 로그인 실패 안내를 보여준다. 전용 “연결 해제 처리 중” 안내는 별도 프론트 보완 사항이다.
- 같은 계정이 재가입 후 다시 탈퇴하면 식별자당 작업 한 행을 새 `PENDING`으로 초기화한다.
- 다중 Worker는 `FOR UPDATE SKIP LOCKED`와 커밋된 상태 전환으로 동일 작업 동시 호출을 피한다.

## DB·개인정보

Application DB에 `kakao_unlink_tasks` 테이블을 추가한다. `users` FK는 없으며 회원 삭제 후에도 남아야 한다.
기본키는 `kakao_id`, 상태·시도 횟수·다음 실행 시각·생성/완료 시각·안전한 오류 코드만 저장한다.
이메일·닉네임·카카오 액세스/리프레시 토큰은 저장하지 않는다. 카카오 ID 역시 식별 정보이므로 접근을 제한한다.

backend의 기존 `ddl-auto: update`로 생성한다. Notification 수동 DDL이나 SQS 계약 변경은 없다.
테스트 DB가 아닌 운영 DB에서 직접 DDL이나 작업 상태를 변경하는 것은 이 구현 검증에 포함하지 않는다.
완료 행은 오래된 로그인 응답 차단에 사용하므로 자동 삭제하지 않는다. 보관 기간은 후속 운영 정책으로 확정한다.

## 설정·배포

backend 전용 환경변수다. **로그인 REST API 키·Client Secret과 다른 서비스 앱 어드민 키**를 사용한다.
로그인에 사용 중인 동일 카카오 앱의 키여야 한다. 소스·PR·채팅·프론트 VITE 변수에 실제 키를 넣지 않는다.

```ini
KAKAO_UNLINK_ENABLED=true
KAKAO_ADMIN_KEY=<server-only service app admin key>
APP_KAKAO_UNLINK_MAX_ATTEMPTS=5
APP_KAKAO_UNLINK_RETRY_DELAY=PT1M
APP_KAKAO_UNLINK_TIMEOUT=PT5S
APP_KAKAO_UNLINK_BATCH_SIZE=10
APP_KAKAO_UNLINK_POLL_MS=5000
```

`KAKAO_UNLINK_ENABLED` 기본값은 false이며 외부 호출 Worker만 제어한다.
**작업 저장·재가입 차단은 항상 적용**하므로 Worker가 꺼져 있거나 공통 스케줄링이 꺼져 있으면 탈퇴 작업이 대기하고 재가입은 차단된다.
운영 배포 전 어드민 키를 준비하고 Worker와 `app.scheduling.enabled` 실효값을 함께 확인해야 한다.
Worker 활성 상태에 키가 비어 있으면 기동을 거부한다. 키를 잘못 입력한 경우 실제 요청은 4xx로 실패할 수 있다.

배포 순서는 키 준비 → 새 backend/테이블 생성 → Worker 활성·스케줄링 확인 → 승인된 테스트 계정 탈퇴·연결 해제 확인이다.
구 backend는 재가입 게이트를 모르므로 처리 중인 작업을 남긴 채 롤백하지 않는다. 구·신 로그인 인스턴스 혼합 운용도 피한다.

## 관측·운영자 확인

Application 스키마 환경에서 다음은 상태 요약 조회만 수행한다.

```sql
SELECT status, COUNT(*), MAX(attempt_count) AS max_attempts,
       MIN(created_at) AS oldest_created_at
FROM application.kakao_unlink_tasks
GROUP BY status;

SELECT status, last_error, COUNT(*)
FROM application.kakao_unlink_tasks
WHERE status IN ('FAILED', 'PROCESSING')
GROUP BY status, last_error;
```

`FAILED` 또는 오래된 `PROCESSING`은 운영자가 확인한다. 전용 관리자 재처리 API는 이번 범위에 없다.
키 오류 수정 후 명확히 실패한 작업을 재시도하는 것과 결과 불명 작업의 대사는 구분한다.
결과 불명은 기존 요청이 종료됐는지·실제 카카오 연결 상태를 확인한 후 승인된 절차로 처리한다.
4xx “이미 연결 안 됨” 응답도 임의로 성공 판정하지 않는다. `COMPLETED`로 바꾸거나 작업을 삭제해 재가입을 강제로 열지 않는다.

## 검증

로컬 임시 PostgreSQL·mock HTTP/Client로 탈퇴/작업/알림 Outbox의 원자성, 재가입 차단, 완료 전 사용자 정보의
지연 도착 차단, 재시도·한도·결과 불명, 다중 Worker 및 로그인/탈퇴 동시성을 검증한다.
실제 카카오 앱 연결 해제·재동의·운영 키/네트워크 검증은 별도 배포 테스트다.
2026-10-30, 배포 기준 `origin/main` (`b8c4e9a`) 위에서 다음 명령을 실행했다.

```bash
./gradlew --offline test bootJar
```

- 전체 backend: 161개 클래스, 총 1,104건 중 실행 1,002건 통과, 실패 0·오류 0·건너뜀 102건. JAR 빌드 성공.
- 건너뜀은 별도 DB 환경변수·벤치마크 등의 조건부 테스트다. 해당 테스트를 실행 완료로 계산하지 않는다.
- 신규 `KakaoUnlinkClientTest` 6건, `KakaoUnlinkConfigTest` 3건, `KakaoUnlinkPostgresTest` 10건은 모두 통과, 건너뜀 0건이다.
- 실제 카카오 서버 호출·운영 키 설정·배포·실제 연결 해제 및 재동의는 미실행이다.
- 프론트와 notification-service 코드는 수정하지 않았다.

공식 API: [카카오 로그인 REST API — 연결 해제](https://developers.kakao.com/docs/ko/kakaologin/rest-api#unlink).
