# 배포 (GitHub Actions → GHCR → EC2 docker compose)

## 구성

지금은 EC2 한 대(t3.small)에 세 컨테이너를 함께 띄운다.

| 컨테이너 | 역할 | 포트 |
|---|---|---|
| `backend` | 회원·판정·검색·추천, 수집 관리자 API 전달 | `BACKEND_PORT`(기본 8080) 공개 |
| `data-service` | 수집·원본 저장·정규화·조건 묶음 (1개만) | 8082, **외부 공개 안 함** (호스트 127.0.0.1에만, 기동 확인용) |
| `postgres-db` | PostgreSQL 16 (`COMPOSE_PROFILES=db`일 때) | 공개 안 함 |

- backend는 compose 네트워크로 `http://data-service:8082`를 부른다 (내부 키 `DATA_SERVICE_INTERNAL_API_KEY`).
- 두 앱 모두 `application_user` 계정·`application` 스키마로 접속한다 (`docs/database-boundaries.md`).
- 수집·정규화는 data-service에서만 돈다. backend의 수집·정규화 실행 플래그는 compose가 `false`로 덮어쓴다 (backend에서 해당 코드를 지우기 전에도 두 곳에서 돌지 않게).
- 나중에 서버를 나누면(DB·메인·수집 각각) 서비스별 compose로 옮기고 `DATA_SERVICE_BASE_URL`·`DB_URL`만 바꾼다.

## 워크플로

| 워크플로 | 하는 일 |
|---|---|
| `backend-image.yml` (Publish backend image) | main push → 테스트 → `ghcr.io/<저장소>/backend:sha-<커밋>` 게시 |
| `data-service.yml` (Data service) | main push 중 `data-service/**`·`database/**` 변경 시 → 테스트 → `ghcr.io/<저장소>/data-service:sha-<커밋>` 게시 |
| `deploy.yml` (Deploy) | 위 둘 중 하나가 **성공하면** 그 서비스만 EC2에 배포 |

배포 순서 (`deploy.yml`)
1. GHCR에 그 커밋 이미지가 있는지 확인
2. compose 파일, DB 초기화 SQL(`db-init/`), 수동 실행용 SQL(`sql/`)을 EC2 배포 폴더로 복사
3. `deploy.env`에서 그 서비스 줄(`BACKEND_IMAGE` 또는 `DATA_SERVICE_IMAGE`)만 바꾸고 `docker compose pull/up -d <서비스>`
4. 최대 3분 응답 확인 (backend `localhost:BACKEND_PORT`, data-service `127.0.0.1:8082`). 응답이 없으면 로그를 남기고 **그 서비스만 이전 이미지로 되돌린 뒤 실패**

- 수동 실행: Actions → Deploy → Run workflow → `service`(backend/data-service), `image_tag`(비우면 최신 커밋, 롤백은 `sha-<40자리 커밋 SHA>`)
- 배포는 한 번에 하나씩 (`concurrency: deploy-production`)
- 저장소 변수 `EC2_HOST`가 비어 있으면 건너뛴다

### 서비스 배포 순서: data-service → backend

backend에서 수집·정규화를 빼는 PR(#45) 이후에는 data-service가 없으면 새 공고가 들어오지 않는다.
**data-service를 먼저 배포**한다. backend를 배포할 때 data-service가 아직 없으면 경고를 남긴다.
data-service 이미지는 main push 때만 게시된다. 배포 경로를 추가한 PR이 병합되면 data-service 이미지가 처음 게시되고 바로 배포된다.

## 처음 한 번 준비할 것

### 1. GHCR
- [ ] 저장소 → Packages → `backend`, `data-service` 각각 Package settings → "Manage Actions access"에 이 저장소가 Read로 있는지 (data-service 패키지는 첫 게시 후 생김)

### 2. EC2
- [ ] 인스턴스 (Ubuntu, t3.small, x86_64). 이미지 플랫폼 기본값이 `linux/amd64`
- [ ] 탄력적 IP
- [ ] 보안 그룹: 22(배포 SSH), `BACKEND_PORT`. **8082·5432는 열지 않는다**
- [ ] Docker, compose 플러그인, **스왑 2GB**(JVM 2개 + PostgreSQL), 시간대

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

- [ ] 배포 폴더와 `.env` (기본 경로 `/home/<EC2_USER>/yon`). 내용은 `deploy/.env.example`을 붙여 넣고 채운다

```bash
mkdir -p ~/yon && cd ~/yon
nano .env
chmod 600 .env
```

### 3. GitHub 설정 (Settings → Secrets and variables → Actions)

| 종류 | 이름 | 값 |
|---|---|---|
| Variable | `EC2_HOST` | EC2 탄력적 IP 또는 도메인 |
| Variable | `EC2_USER` | `ubuntu` |
| Variable | `EC2_DEPLOY_DIR` | (선택) 기본값 `/home/<EC2_USER>/yon` |
| Secret | `EC2_SSH_KEY` | EC2 접속용 **배포 전용** 개인키 전체 |

### 4. DB 준비

아래 명령은 배포 폴더에서 실행한다.

```bash
c() { docker compose -f docker-compose.prod.yml --env-file .env --env-file deploy.env "$@"; }
```

**A. 새 볼륨 (처음 띄우는 DB)**
`db-init/01-database-boundaries.sql`이 빈 볼륨에서 자동 실행되어 `application_user`·`notification_user` 계정과 스키마를 만든다. 비밀번호만 따로 넣는다 (SQL 파일·셸 기록에 남기지 않기 위해 대화형으로).

```bash
c up -d postgres-db
c exec postgres-db psql -U postgres -d yon
```
```text
\password application_user
\password notification_user
\q
```
`application_user` 비밀번호는 `.env`의 `APPLICATION_DB_PASSWORD`와 같게 한다. `notification_user`는 알림 서버를 띄울 때 정해도 된다.

**B. 기존 볼륨 (public 스키마에 이미 테이블이 있는 DB)**
초기화 SQL은 빈 볼륨에서만 돌므로 직접 실행한다. 앱을 멈추고 백업한 뒤 진행한다 (`docs/database-boundaries.md`의 1·2단계).

```bash
c stop backend data-service
c exec -T postgres-db pg_dump -U postgres -d yon -Fc > ~/yon-backup-$(date +%Y%m%d%H%M).dump
c exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1 < db-init/01-database-boundaries.sql
c exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1 < sql/migrate-public-to-application.sql
```
그다음 A와 같이 `\password`로 비밀번호를 넣는다.

**C. 정규화 요청 NOTIFY 트리거 (data-service 첫 기동 후 한 번)**
data-service가 처음 뜨면서 테이블을 만든 뒤 실행한다. 반복 실행해도 된다. 없어도 1분 폴링으로 정규화는 되지만 즉시 시작되지 않는다.

```bash
{ echo 'SET ROLE application_user;'; cat sql/normalization-notifications.sql; } | c exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1
```

**D. 지역 사전**
지역 사전(`regions`)이 비어 있으면 data-service 첫 기동 때만 `.env`의 `REGION_CODE_SYNC_ON_STARTUP=true`로 띄우고, 끝나면 `false`로 돌린다 (`c up -d data-service`).

## 운영 중 자주 쓰는 명령 (배포 폴더)

```bash
c ps
c logs -f --tail 200 backend
c logs -f --tail 200 data-service
c restart data-service
cat deploy.env deploy.env.prev
docker stats --no-stream
```
(`restart`는 `.env`를 바꾼 뒤. 평소 배포·롤백은 Actions 수동 실행으로 한다)

## 주의
- **메모리**: t3.small(2GB)에 PostgreSQL과 JVM 두 개를 띄운다. 힙 상한은 `.env`의 `BACKEND_JAVA_OPTS`(기본 `-Xmx512m`), `DATA_SERVICE_JAVA_OPTS`(기본 `-Xmx384m`). 스왑은 필수. 배포 후 `docker stats`로 확인하고 조정한다.
- **data-service는 하나만**: 수집 스케줄러·대조가 중복 실행되지 않게 컨테이너를 늘리지 않는다.
- **SSH 22번 포트**: GitHub 호스팅 러너 IP 대역이 넓다. 비밀번호 로그인 금지, 배포 전용 키 사용. 나중에 SSM 접속 검토.
- **DB 백업**: compose 안 PostgreSQL은 도커 볼륨(`yon_postgres_data`)에 저장된다. 인스턴스를 지우면 함께 사라진다.
- **`ddl-auto: update`**: enum CHECK 제약은 갱신하지 않는다. enum 값이 늘어나는 배포는 제약 재생성 SQL을 먼저 실행한다.
- **테스트**: 테스트는 이미지 게시 워크플로가 돌린다. 테스트가 실패하면 이미지도 배포도 없다.
