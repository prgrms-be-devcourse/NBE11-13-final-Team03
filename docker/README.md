# 로컬 Docker 실행

DB, data-service, backend, notification-service, nginx를 `docker/compose.yaml` 하나로 띄운다.
기동 순서는 compose가 healthcheck로 맞춘다(DB → 세 서버 → nginx).

| 서비스 | 내용 | 호스트 포트 |
| --- | --- | --- |
| `postgres-db` | PostgreSQL 16, 첫 기동 시 DB 초기화 | 5432 |
| `data-service` | 수집·정규화 서버 | 공개하지 않음 |
| `backend` | Application 서버 | 8080 (디버깅용) |
| `notification-service` | Notification 서버 | 공개하지 않음 |
| `nginx` | 브라우저 진입점 | 80 |

## 준비

1. Docker와 Java 25를 설치한다.
2. `docker/.env.example`을 `docker/.env`로 복사하고 값을 채운다. `.env`는 커밋하지 않는다.
3. 카카오 로그인을 확인하려면 카카오 개발자 콘솔에 Redirect URI
   `http://localhost/login/oauth2/code/kakao`를 등록한다. `HTTP_PORT`를 바꾸면 포트도 포함해 등록한다.

## 실행과 종료

`docker/` 폴더에서 실행한다.

```bash
./start.sh                                  # JAR 빌드 후 전체 기동
./start.sh --no-build                       # 이미 빌드한 JAR·이미지로 기동
docker compose up -d postgres-db            # DB만 (IDE·bootRun 개발용)
docker compose up -d --build backend        # backend만 다시 빌드·재시작 (먼저 backend에서 ./gradlew bootJar)
docker compose logs -f backend              # 로그 보기
docker compose down                         # 종료, DB 데이터 유지
docker compose down -v                      # 볼륨까지 삭제 (로컬 DB 데이터 삭제, 다음 기동 때 초기화 다시 실행)
```

Dockerfile이 빌드된 Boot JAR를 복사하므로, 서버 이미지를 다시 만들기 전에는 해당 프로젝트에서 `./gradlew bootJar`를 먼저 실행한다. `start.sh`는 이 과정을 함께 한다.

## 접속

| 대상 | 주소 |
| --- | --- |
| 브라우저 진입점(nginx) | http://localhost |
| backend 직접(디버깅, Swagger) | http://localhost:8080/swagger-ui.html |
| PostgreSQL(호스트) | localhost:5432 |
| data-service | 호스트에 공개하지 않음. backend가 `static://data-service:9092` gRPC로 호출(수집 관리자 API 전달) |
| notification-service | 호스트에 공개하지 않음. 컨테이너끼리 `http://notification-service:8081`로 호출 |

포트가 이미 쓰이고 있으면 `docker/.env`의 `DB_PORT`, `BACKEND_PORT`, `HTTP_PORT`를 바꾼다.

## DB 초기화

빈 볼륨으로 처음 기동할 때만 다음이 순서대로 실행된다. 자세한 내용은 [DB 경계 문서](../docs/database-boundaries.md)를 따른다.

1. 계정·스키마 생성(`database/scripts/database-boundaries.sql`)
2. 두 계정 비밀번호 설정(`database/local/02-set-local-passwords.sh`)
3. Notification 테이블 생성(`database/local/03-apply-notification-ddl.sh`)

application 스키마 테이블은 data-service(수집·정규화 테이블)와 backend(그 밖의 테이블)가 첫 기동에서 `ddl-auto: update`로 만든다.
정규화 요청 NOTIFY 트리거(`normalization-notifications.sql`)는 테이블이 생긴 뒤에만 설치할 수 있어 초기화에 넣지 않았다. 트리거가 없어도 data-service의 폴링으로 정규화는 진행된다.
초기화 스크립트나 DDL을 바꿨다면 `docker compose down -v` 후 다시 기동해야 반영된다.

### 예전 `backend/docker-compose.yml`에서 옮겨 올 때

이 구성의 DB 볼륨은 `yon-postgres-data`로, 예전 구성의 볼륨(`backend_postgres_data`)과 다르다.
그래서 기본으로는 **빈 DB로 새로 시작**하고, 예전 로컬 데이터는 이어지지 않는다(예전 볼륨은 삭제되지 않고 남는다).

- 두 구성 모두 호스트 5432를 쓰므로, 먼저 `backend/`에서 `docker compose down`으로 예전 DB를 내린다.
- 예전 데이터를 계속 쓰려면 `docker/.env`에 `YON_DB_VOLUME=backend_postgres_data`를 넣는다.
  예전 구성은 3번(Notification 테이블)을 자동으로 실행하지 않았으므로, 기동 전에 `notification_user`로
  `database/scripts/notification-inbox.sql` → `database/scripts/notification-user-api.sql` →
  `database/scripts/notification-withdrawal.sql` → `database/scripts/notification-delivery.sql` 순서로 한 번씩 적용해야
  notification-service가 뜬다(`ddl-auto: validate`). 이미 적용된 SQL은 재실행하지 않고 미적용분만 순서대로 적용한다.

## 알림 전달

`NOTIFICATION_PUBLISHER_ENABLED`, `NOTIFICATION_RECEIVER_ENABLED`는 기본 `false`다.
탈퇴 사용자 차단(Tombstone)이 구현되기 전까지 켜지 않는다.
켤 때는 두 서버가 같은 `NOTIFICATION_INTERNAL_API_KEY`를 사용한다.

## 수집·정규화

`COLLECTION_SCHEDULE_ENABLED`는 예시에서 `false`다. 정기 수집은 외부 API를 호출하므로 키가 없으면 끄고,
필요할 때 관리자 API(`/api/admin/collection/**`, backend가 data-service로 전달)로 수동 수집한다.
`DATA_SERVICE_INTERNAL_API_KEY`는 backend와 data-service 둘 다 없으면 기동하지 않으므로 반드시 채운다.

## 운영 배포와의 관계

이 구성은 로컬 개발용이다. 운영은 CI가 만든 GHCR 이미지와 서버별 배포 compose(`deploy/db`, `deploy/main`, `deploy/data`, #56)를 쓴다.
운영 메인 서버에 notification-service와 Notification DDL 선적용을 넣는 작업은 별도 PR에서 진행한다.
