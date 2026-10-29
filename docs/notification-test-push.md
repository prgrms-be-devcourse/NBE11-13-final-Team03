# 시연용 즉시 푸시 발송

기존 인앱 알림을 지정한 테스트 사용자의 Endpoint 한 개로 즉시 발송한다.
내부 API → 기존 `PushSender` → Firebase HTTP v1 → 브라우저 경로를 확인하는 용도다.
기본 비활성이며 backend Facade·프론트 라우트·공개 포트를 추가하지 않는다.

## 동작과 제한

- `POST /internal/notifications/test-push`, `X-INTERNAL-KEY` 인증. 일반 수신/Facade 키와 별도 키를 사용한다.
- 허용 사용자 목록에 있는 사용자만 대상으로 삼으며 알림과 Endpoint 모두 그 사용자 소유여야 한다.
- 알림 제목은 `[시연]` 또는 `[테스트]`로 시작해야 한다. 임의 토큰·본문·링크를 요청으로 받지 않는다.
- 발송 직전 사용자 잠금 아래 탈퇴·명시적 푸시 동의·활성 Endpoint·인앱/푸시 만료 중 이른 시각을 확인한다.
  정상 Worker와 마찬가지로 사전 검사 트랜잭션 종료 후 FCM을 호출하므로, 검사 이후 동의 철회/탈퇴와의 작은 경합 구간은 존재한다.
- 슬롯 시간·사용 횟수를 우회하는 명시적인 테스트 발송이다. 기존 대기열·묶음·Delivery·슬롯 횟수를 변경하지 않는다.
  새 테이블/DDL은 필요 없다. Outbox/SQS/자동 판정/슬롯 스케줄링의 검증은 기존 흐름에서 별도로 수행한다.
- `FCM_ACCEPTED`는 FCM 수락이며 브라우저 표시/사용자 읽음의 증명이 아니다.
- `FCM_UNREGISTERED`이면 보낸 토큰과 아직 같은 Endpoint만 비활성화한다. 토큰 교체가 있었다면 새 토큰은 보존한다.
- 호출 한 번에 한 번만 시도하며 자동 재시도하지 않는다. 타임아웃 시 FCM이 이미 수락했을 수도 있으므로 바로 반복하지 않는다.
  동일 알림을 반복 발송하면 브라우저가 같은 알림 tag를 교체하면서 새 팝업을 생략할 수 있다. 반복 시연은 새 시연 알림을 사용한다.
- `requestId`, 사용자/알림/Endpoint ID, 성공/실패 종류를 서버 로그에 기록한다. 토큰·본문·서비스 계정 정보는 기록하지 않는다.
  테스트 발송 영수증은 HTTP 응답과 로그로 확인하며 일반 Delivery 통계에는 포함되지 않는다.

## 환경 설정

알림 서버의 `notification.env`에 설정한다. 실제 키는 저장소/프론트에 넣지 않는다.

```ini
NOTIFICATION_PUSH_ENABLED=true
NOTIFICATION_TEST_PUSH_ENABLED=true
NOTIFICATION_TEST_PUSH_API_KEY=<별도로 생성한 시연 전용 키>
NOTIFICATION_TEST_PUSH_ALLOWED_USER_IDS=1
```

여러 사용자를 허용할 때는 `1,3`처럼 쉼표로 구분한다. 비어 있거나 양수가 아닌 ID이면 활성화 시 기동에 실패한다.
기존 FCM 프로젝트 ID·HTTPS public base URL·Firebase Admin 인증 파일 설정도 필요하다.
이 API를 켠다고 HTTP 수신이나 사용자 API가 활성화되지는 않는다. `NOTIFICATION_PUSH_ENABLED=false`이면 발송은 503으로 거부한다.
설정 변경 후 notification-service 컨테이너를 재생성하고 기동 완료를 확인한다.

## 호출 전 확인 (DB 서버 psql)

```sql
SELECT id, user_id, title, expires_at, push_expires_at
FROM notification.notifications
WHERE id = 7 AND user_id = 1;

SELECT id, user_id, active
FROM notification.notification_endpoints
WHERE user_id = 1
ORDER BY id DESC;
```

`7`과 `32`는 시연 당시 예시 ID다. 현재 존재하는 시연 알림과 활성 Endpoint ID로 바꾼다.
가능하면 시연 알림을 `channels=["IN_APP"]`로 먼저 수신시켜 일반 슬롯에서 나중에 중복 발송되지 않게 한다.
이미 일반 슬롯 큐에 들어간 알림을 테스트 발송해도 기존 큐는 취소되지 않는다.

## 호출 (메인 EC2)

아래 예시는 메인 서버의 Python 표준 라이브러리와 notification-service의 Compose 내부 IP를 사용한다.
호스트에 8081 포트를 공개하지 않으며, 키는 입력 프롬프트에서 읽어 명령 이력·프로세스 인자에 남기지 않는다.
notification-service의 내부 HTTP 포트는 현재 배포 설정의 8081이다.

```bash
python3 - <<'PY'
import getpass
import json
import subprocess
import urllib.error
import urllib.request

container = json.loads(subprocess.check_output(
    ["docker", "inspect", "yon-main-notification-service-1"], text=True))[0]
networks = list(container["NetworkSettings"]["Networks"].values())
address = next(network["IPAddress"] for network in networks if network.get("IPAddress"))
key = getpass.getpass("시연 전용 API 키: ")
body = json.dumps({"userId": 1, "notificationId": 7, "endpointId": 32}).encode()
request = urllib.request.Request(
    f"http://{address}:8081/internal/notifications/test-push",
    data=body,
    headers={"Content-Type": "application/json", "X-INTERNAL-KEY": key},
    method="POST")
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
try:
    with opener.open(request, timeout=40) as response:
        print(response.status, response.read().decode())
except urllib.error.HTTPError as error:
    print(error.code, error.read().decode())
PY
```

응답 예시:

```json
{"requestId":"<UUID>","notificationId":7,"endpointId":32,"status":"FCM_ACCEPTED","fcmMessageId":"projects/<project>/messages/<receipt>"}
```

브라우저를 실행한 채 다른 탭/앱으로 이동하고 OS 집중 모드·사이트/OS 알림 허용을 확인한다.
실패 시 HTTP 코드와 안전한 오류 코드를 반환한다.

| HTTP | 의미 |
| --- | --- |
| 400 | 필수 ID 누락·0/음수·잘못된 JSON |
| 401 | 전용 키 누락·불일치·중복 헤더 |
| 403 | 기능 비활성 상태의 경로 접근 또는 허용 목록 밖 사용자 |
| 404 | 알림/Endpoint 없음 또는 소유자 불일치 |
| 409 | 시연 제목 아님·동의 철회·비활성 Endpoint·만료·FCM_UNREGISTERED |
| 410 | 탈퇴 사용자 |
| 502 | FCM 일시/영구/인증·설정 오류. 자동 재시도 없음 |
| 503 | 전역 푸시 비활성 또는 내부 처리 실패 |

로그 조회:

```bash
docker logs --since 5m yon-main-notification-service-1 2>&1 | grep 'Test push'
```

시연 종료 후 `NOTIFICATION_TEST_PUSH_ENABLED=false`로 복구하고 컨테이너를 재생성한다.
이후 동일 경로가 403으로 차단되는지 확인한다. 일반 푸시/슬롯 설정은 그대로 유지한다.

## 검증

2026-10-29 로컬에서 notification-service 전체 `./gradlew --offline test bootJar` 실행:
30개 클래스·212개 테스트, 실패 0·오류 0·건너뜀 0, 실행 JAR 빌드 성공.
신규 테스트는 전용 키/비활성 경로/입력 검증, 허용 사용자·소유권·동의·탈퇴·만료 검사,
무효 토큰 비활성화 시 교체 토큰 보존, FCM 실패 시 자동 재시도 금지·민감 정보 비노출을 확인한다.
대상 조회 SQL은 H2에서 검증하고 PostgreSQL 전용 사용자 잠금은 기존 구현을 재사용한다.
신규 API 테스트의 FCM 발송기는 mock이며 실제 브라우저 수신은 배포 후 확인한다.
