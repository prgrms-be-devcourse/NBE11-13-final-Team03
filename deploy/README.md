# 배포 (GitHub Actions → GHCR → EC2 docker compose)

## 구성: EC2 3대 (모두 t3.small)

| 서버 | 컨테이너 | 폴더 | 배포 |
|---|---|---|---|
| DB 서버 | PostgreSQL 16 | `deploy/db` | 수동 (처음 한 번) |
| 메인 서버 | `backend`, `notification-service` (컨테이너만 나눠 한 서버) | `deploy/main` | 자동 (`Publish backend image`·`Notification service` 성공 시 그 서비스만) |
| 데이터 서버 | `data-service` (수집·원본 저장·정규화·조건 묶음, **1개만**) | `deploy/data` | 자동 (`Data service` 성공 시) |

- 프론트(React)는 S3 + CloudFront로 따로 배포한다 (이 폴더 범위 밖).
- 서버끼리는 **같은 VPC의 사설 IP**로 통신한다.
  - 메인·데이터 → DB: `DB_URL=jdbc:postgresql://<DB 사설 IP>:5432/yon`
  - 메인 → 데이터: `DATA_SERVICE_GRPC_ADDRESS=static://<데이터 사설 IP>:9092` (내부 키 `DATA_SERVICE_INTERNAL_API_KEY`)
- 두 앱 모두 `application_user` 계정·`application` 스키마로 접속한다 (`docs/database-boundaries.md`).
- 수집·정규화는 데이터 서버에서만 돈다. backend의 수집·정규화 실행 플래그는 메인 compose가 `false`로 덮어쓴다.

### 보안 그룹

| 보안 그룹 | 인바운드 |
|---|---|
| `yon-db` | 5432 ← `yon-main`, `yon-data` 보안 그룹만 / 22 |
| `yon-main` | `BACKEND_PORT`(8080) ← 공개 / 22 |
| `yon-data` | 9092 ← `yon-main` 보안 그룹만 / 22 ← `yon-main` 보안 그룹만 (배포·관리 SSH 모두 메인 서버 경유) |

탄력적 IP는 계정당 1개라 메인 서버에만 둔다. 데이터·DB 서버의 공개 IP는 재시작마다 바뀌므로 접속·배포 모두 메인 서버를 거쳐 사설 IP로 한다.

**5432·9092는 0.0.0.0/0으로 열지 않는다.** data-service는 Spring Security가 없다.
한 겹 더: DB 서버 `.env`의 `DB_BIND_IP`, 데이터 서버 `.env`의 `DATA_SERVICE_BIND_IP`에 각 서버의 사설 IP를 넣으면 포트가 사설 IP에만 바인딩된다 (보안 그룹 실수 대비).

### 운영 진입점 (TLS): 미정

지금 메인 서버는 TLS 없이 8080(HTTP)을 바로 연다. 이 상태로는 실제 사용자 접속을 받지 않는다.
- `AUTH_COOKIE_SECURE=true`(Secure 쿠키)는 HTTP 응답에서 브라우저가 저장·전송하지 않아 로그인·토큰 갱신이 안 된다 → TLS 전에는 `false`
- 프론트가 S3+CloudFront(HTTPS)면 `http://<IP>:8080` API 호출은 mixed content로 차단된다
- 카카오 redirect-uri가 `{baseUrl}` 기반이라, TLS를 붙이면 카카오 콘솔 등록 주소도 바꿔야 한다

EC2 재구축 전에 TLS 종단(nginx+certbot 또는 ALB/CloudFront)을 정하고, 그에 맞춰 `deploy/main/.env.example`(`AUTH_COOKIE_SECURE=true`)과 보안 그룹(443)을 바꾼다.

## 워크플로

| 워크플로 | 하는 일 |
|---|---|
| `backend-image.yml` (Publish backend image) | main push → 테스트 → `ghcr.io/<저장소>/backend:sha-<커밋>` 게시 |
| `data-service.yml` (Data service) | main push 중 `data-service/**`·`database/**` 변경 시 → 테스트 → `ghcr.io/<저장소>/data-service:sha-<커밋>` 게시 |
| `notification-service.yml` (Notification service) | main push 중 `notification-service/**` 등 변경 시 → 테스트 → `ghcr.io/<저장소>/notification-service:sha-<커밋>` 게시 |
| `deploy.yml` (Deploy) | 위 셋 중 하나가 **성공하면** 그 서비스를 그 서버에 배포 |

배포 순서 (`deploy.yml`)
1. 서비스의 서버 변수(`EC2_MAIN_HOST` / `EC2_DATA_HOST`)가 비어 있으면 건너뜀
2. GHCR에 그 커밋 이미지가 있는지 확인
3. 그 서버 폴더의 `docker-compose.yml`을 배포 폴더로 복사
4. `deploy.env`의 이미지 줄(`BACKEND_IMAGE` / `DATA_SERVICE_IMAGE` / `NOTIFICATION_IMAGE`)만 바꾸고 `docker compose pull/up -d <서비스>`
5. 약 3분 동안 backend는 HTTP 8080 응답을, data-service는 컨테이너 안에서 인증된 gRPC 9092 읽기 RPC를, notification-service는 컨테이너 안의 8081 포트를 확인한다. 실패하면 로그를 남기고 **이전 이미지로 되돌린 뒤 실패**

- 수동 실행: Actions → Deploy → Run workflow → `service`(backend/data-service/notification-service), `image_tag`(비우면 최신 커밋, 롤백은 `sha-<40자리 커밋 SHA>`)
- 배포는 한 번에 하나씩 (`concurrency: deploy-production`)

### 서버 준비 순서: DB → 데이터 → 메인

backend에서 수집·정규화를 빼는 PR(#45) 이후에는 데이터 서버가 없으면 새 공고가 들어오지 않는다.
backend를 배포할 때 `EC2_DATA_HOST`가 비어 있으면 경고를 남긴다.

## 처음 한 번 준비할 것

### 1. GHCR
- [ ] 저장소 → Packages → `backend`, `data-service` 각각 Package settings → "Manage Actions access"에 이 저장소가 Read로 있는지 (data-service 패키지는 첫 게시 후 생김)

### 2. EC2 3대 공통
- [ ] Ubuntu, t3.small, x86_64 (이미지 플랫폼 기본값 `linux/amd64`), 같은 VPC·서브넷
- [ ] 공개 IP: 메인 서버는 탄력적 IP(사용자 접속). 데이터 서버는 배포 SSH용, DB 서버는 관리 SSH용 공개 IP가 필요하다 (서버끼리는 사설 IP)
- [ ] 보안 그룹 (위 표)
- [ ] Docker, compose 플러그인(**Docker Compose 2.24 이상**: `docker compose version`. 메인 compose의 `env_file` `path`/`required` 문법), 스왑, 시간대

```bash
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-v2
sudo systemctl enable --now docker
sudo usermod -aG docker ubuntu
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile swap swap defaults 0 0' | sudo tee -a /etc/fstab
sudo timedatectl set-timezone Asia/Seoul
```

- [ ] 배포 전용 SSH 공개키를 메인·데이터 서버의 `~/.ssh/authorized_keys`에 추가 (같은 키)

### 3. GitHub 설정 (Settings → Secrets and variables → Actions)

| 종류 | 이름 | 값 |
|---|---|---|
| Variable | `EC2_MAIN_HOST` | 메인 서버 탄력적 IP 또는 도메인 |
| Variable | `EC2_DATA_HOST` | 데이터 서버 사설 IP (`EC2_JUMP_HOST`를 쓸 때). 쓰지 않으면 공개 IP |
| Variable | `EC2_JUMP_HOST` | (선택) 메인 서버 탄력적 IP. 있으면 데이터 서버 배포는 메인 서버를 거쳐 SSH한다 (ProxyJump) |
| Variable | `EC2_USER` | `ubuntu` |
| Variable | `EC2_DEPLOY_DIR` | (선택) 기본값 `/home/<EC2_USER>/yon` |
| Secret | `EC2_SSH_KEY` | **배포 전용** 개인키 전체 (두 서버 공용) |

예전 단일 서버용 `EC2_HOST` 변수는 더 이상 쓰지 않는다 (지워도 됨).

## DB 서버

### A. 띄우기 (처음 한 번)

노트북의 저장소 루트에서 파일을 올린다.

```bash
ssh ubuntu@<DB 서버> 'mkdir -p ~/yon-db/db-init ~/yon-db/sql'
scp deploy/db/docker-compose.yml deploy/db/.env.example ubuntu@<DB 서버>:~/yon-db/
scp database/scripts/database-boundaries.sql ubuntu@<DB 서버>:~/yon-db/db-init/01-database-boundaries.sql
scp database/scripts/migrate-public-to-application.sql data-service/src/main/resources/db/scripts/normalization-notifications.sql ubuntu@<DB 서버>:~/yon-db/sql/
```

DB 서버에서:

```bash
cd ~/yon-db
cp .env.example .env && nano .env && chmod 600 .env
docker compose up -d
docker compose exec postgres-db psql -U postgres -d yon
```
```text
\password application_user
\password notification_user
\q
```

`db-init/01-database-boundaries.sql`이 빈 볼륨에서 자동 실행되어 `application_user`·`notification_user` 계정과 스키마를 만든다. 비밀번호는 SQL 파일·셸 기록에 남기지 않도록 대화형으로 넣는다. `application_user` 비밀번호는 메인·데이터 서버 `.env`의 `APPLICATION_DB_PASSWORD`와 같게 한다.

### B. 기존 데이터를 옮겨 오는 경우

예전 DB의 `pg_dump`(public 스키마)를 복원한 뒤 경계 SQL과 이전 SQL을 실행한다 (`docs/database-boundaries.md`의 1·2단계).

```bash
docker compose exec -T postgres-db pg_restore -U postgres -d yon --no-owner < ~/yon-old.dump
docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1 < db-init/01-database-boundaries.sql
docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1 < sql/migrate-public-to-application.sql
```
그다음 A와 같이 `\password`로 비밀번호를 넣는다.

### C. 정규화 요청 NOTIFY 트리거 (data-service 첫 기동 후 한 번)

data-service가 처음 뜨면서 테이블을 만든 뒤 DB 서버에서 실행한다. 반복 실행해도 된다. `SET ROLE`은 계정에 설정된 `search_path`를 적용하지 않으므로 스키마를 함께 지정한다. 없어도 1분 폴링으로 정규화는 되지만 즉시 시작되지 않는다.

```bash
cd ~/yon-db
{ echo 'SET ROLE application_user;'; echo 'SET search_path TO application, pg_catalog;'; cat sql/normalization-notifications.sql; } | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1
```

### D. 정규화 ID 시퀀스 준비 (데이터가 있는 DB에 시퀀스 ID 버전 data-service를 처음 배포하기 전 한 번)

정규화 엔티티 ID를 IDENTITY → 시퀀스로 바꾼 버전(docs/ingest-write-tuning.md)을 처음 배포하기 **전에** 실행한다. 빠뜨리면 시퀀스가 1부터 만들어져 정규화가 PK 충돌로 멈춘다. 새 DB는 필요 없다.

```bash
scp database/scripts/opportunity-id-sequences.sql database/scripts/opportunity-id-identity-rollback.sql ubuntu@<DB 서버>:~/yon-db/sql/
```
DB 서버에서:
```bash
cd ~/yon-db
{ echo 'SET ROLE application_user;'; cat sql/opportunity-id-sequences.sql; } | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1
```
IDENTITY 버전으로 되돌릴 때는 이전 버전을 띄우기 전에 같은 방식으로 `sql/opportunity-id-identity-rollback.sql`을 실행한다.

### 백업

DB는 DB 서버의 도커 볼륨(`yon-db_postgres_data`)에 있다. 인스턴스를 지우면 함께 사라진다.
아래는 수동 백업이다. 인스턴스 유실에 대비한 주기 백업(cron + S3 업로드)은 후속으로 추가한다.

```bash
cd ~/yon-db
docker compose exec -T postgres-db pg_dump -U postgres -d yon -Fc > ~/yon-backup-$(date +%Y%m%d%H%M).dump
```

## 데이터 서버

```bash
mkdir -p ~/yon && cd ~/yon
nano .env && chmod 600 .env
```

`.env`는 `deploy/data/.env.example`을 붙여 넣고 채운다. 그다음 Actions → Deploy → `data-service`로 배포한다 (또는 data-service 변경이 main에 병합되면 자동).

**지역 사전**: `regions`가 비어 있으면 첫 기동 때만 `.env`의 `REGION_CODE_SYNC_ON_STARTUP=true`로 띄우고, 끝나면 `false`로 돌린 뒤 재시작한다.

배포 워크플로는 컨테이너 내부의 gRPC 9092에 인증된 읽기 RPC를 호출한다. DATA_SERVICE_BIND_IP는 외부 메인 서버가 접속할 사설 IP 바인딩에 사용한다.

## 메인 서버

```bash
mkdir -p ~/yon && cd ~/yon
nano .env && chmod 600 .env
```

`.env`는 `deploy/main/.env.example`을 붙여 넣고 채운다. 그다음 Actions → Deploy → `backend`로 배포한다.

### 알림 서버 (notification-service)

backend와 같은 서버에서 컨테이너만 나눠 돈다. 호스트 포트를 열지 않고 backend만 compose 네트워크 안에서 `http://notification-service:8081`로 부른다 (compose가 backend에 `NOTIFICATION_BASE_URL`을 넣는다).
`ddl-auto: validate`라 테이블을 먼저 만들어야 뜬다.

1. DB 서버에서 알림 테이블 생성 (처음 한 번, `notification_user`로. 순서는 `database/local/03-apply-notification-ddl.sh`와 같다)

```bash
scp database/scripts/notification-{inbox,user-api,withdrawal,delivery,admin}.sql ubuntu@<DB 서버>:~/yon-db/sql/
```
DB 서버에서:
```bash
cd ~/yon-db
for f in inbox user-api withdrawal delivery admin; do
  { echo 'SET ROLE notification_user;'; echo 'SET search_path TO notification, pg_catalog;'; cat sql/notification-$f.sql; } | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1 || break
done
```

2. 메인 서버 배포 폴더에 `notification.env` 작성 (`deploy/main/notification.env.example`, `chmod 600`). backend `.env`와 파일을 나눠 `application_user` 비밀번호가 알림 컨테이너에 들어가지 않게 한다.
3. Actions → Deploy → `notification-service`로 배포한다 (또는 `notification-service` 변경이 main에 병합되면 자동). 이미지는 알림 서버 변경이 있었던 커밋에만 있으므로 수동 배포 때는 `image_tag`에 그 커밋을 넣는다.

수신·사용자 API·푸시·SQS 기능은 모두 기본 꺼짐이다. 켜는 시점과 값(`NOTIFICATION_INTERNAL_API_KEY` 등)은 알림 담당이 정하고, backend `.env`의 `NOTIFICATION_PUBLISHER_ENABLED`·`NOTIFICATION_FACADE_ENABLED`와 함께 켠다.

기능을 켜기 전에 추가로 적용할 SQL (위 1번과 같은 방식, `notification_user`, 알림 서버를 멈춘 상태에서)
- 푸시 슬롯 모드(`NOTIFICATION_PUSH_SLOTS_ENABLED`): `notification-push-slots.sql`. 푸시 만료 분리 이전의 delivery SQL을 적용했던 기존 DB는 그 전에 `notification-push-expiry.sql` (새 DB는 delivery SQL에 포함)
- 새 적합 알림 우선순위(#80 머지 후): `notification-new-match-priority.sql`
- 그 밖에 알림 SQL이 추가되면 파일 머리말의 적용 시점을 따르고 이 목록에 더한다

## 운영 중 자주 쓰는 명령 (메인·데이터 서버의 배포 폴더)

```bash
c() { docker compose -f docker-compose.yml --env-file .env --env-file deploy.env "$@"; }
c ps
c logs -f --tail 200
c restart
cat deploy.env deploy.env.prev
docker stats --no-stream
free -h
```
(`restart`는 `.env`를 바꾼 뒤. 평소 배포·롤백은 Actions 수동 실행으로 한다)

배포가 3분 안에 응답하지 않으면 이전 이미지로 되돌린다. 첫 배포라 이전 이미지가 없으면 재시작 반복을 막기 위해 그 서비스를 멈추고(`compose stop`) 실패한다. 로그를 보고 `.env`를 고친 뒤 Actions에서 다시 배포한다.

## 주의
- **메모리**: 서버마다 2GB. 힙은 비율로 잡는다. 메인 서버는 backend `-XX:MaxRAMPercentage=50.0` + 알림 서버 `25.0`, 데이터 서버는 `70.0`(`.env`의 `BACKEND_JAVA_OPTS`·`NOTIFICATION_JAVA_OPTS`·`DATA_SERVICE_JAVA_OPTS`로 조정). 스왑은 필수. 배포 후 `docker stats`(컨테이너별)와 `free -h`(스왑 사용량)로 확인한다. 힙 합계가 1.5GB라 비힙까지 2GB를 넘기 쉬우므로, 스왑이 계속 쓰이면 컨테이너 `mem_limit`(이때 비율은 컨테이너 한도 기준)를 둔다.
- **data-service는 하나만**: 수집 스케줄러·대조가 중복 실행되지 않게 컨테이너·서버를 늘리지 않는다.
- **SSH 22번 포트**: GitHub 호스팅 러너 IP 대역이 넓다. 비밀번호 로그인 금지, 배포 전용 키 사용. 나중에 SSM 접속 검토.
- **`ddl-auto: update`**: enum CHECK 제약은 갱신하지 않는다. enum 값이 늘어나는 배포는 제약 재생성 SQL을 먼저 실행한다.
- **테스트**: 테스트는 이미지 게시 워크플로가 돌린다. 테스트가 실패하면 이미지도 배포도 없다.


### 수집 gRPC 배포 점검과 롤백

운영 backend는 DATA_SERVICE_GRPC_ADDRESS=static://<데이터 서버 사설 IP>:9092를 사용한다.
DATA_SERVICE_BIND_IP는 필수이며 데이터 서버 사설 IP를 넣는다. 포트 매핑과 Dockerfile은 9092만 제공한다.
로컬 compose의 서비스 DNS(data-service)는 다른 EC2에서 해석되지 않는다.

배포 워크플로는 data-service 컨테이너 내부에서 CollectionGrpcProbe를 별도 JVM으로 실행한다.
기존 gRPC 클라이언트 의존성을 재사용하며, 환경변수의 전용 키로 한국 날짜의 ListSyncJobsByDate를 호출한다.
응답 목록이 비어 있어도 정상이며, 인증 실패·DB 오류·기한 초과는 점검 실패다.
키·원격 오류 본문은 출력하지 않는다. backend 키(INTERNAL_API_KEY)는 사용하지 않는다.
TCP healthcheck와 RPC 조회 성공이 복구·백필 같은 모든 기동 작업 완료를 보장하지는 않는다.
이 점검은 컨테이너 내부 연결이므로 실제 main→data 사설망·보안 그룹은 backend 관리자 API로 별도 확인한다.

자동 실패 시 이전 deploy.env로 돌아가는 동작은 유지한다.
새 점검 프로그램이 없는 과거 data-service 이미지를 수동 배포할 때는 해당 커밋의 워크플로를 선택하거나
서버에서 compose로 이미지를 되돌리고 그 버전의 점검 방식으로 확인한다. REST backend로 롤백하려면
REST를 제공하는 data-service도 함께 되돌려야 한다.

현재는 기존 REST와 같은 사설 VPC·사설 IP 바인딩·yon-main 보안 그룹 제한 및 전용 내부 키 전제로
평문 gRPC를 유지한다. **TLS 필수 여부와 인증서 운영은 팀 논의가 필요한 미결정 사항**이다.
내부 키는 암호화를 제공하지 않으며 공개망에 9092를 노출하지 않는다.
