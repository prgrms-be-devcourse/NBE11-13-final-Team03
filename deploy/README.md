# 배포 (GitHub Actions → GHCR → EC2 docker compose)

## 구성: EC2 4대 (모두 t3.small)

| 서버 | 컨테이너 | 폴더 | 배포 |
|---|---|---|---|
| DB 서버 | PostgreSQL 16 (primary) | `deploy/db` | 수동 (처음 한 번) |
| DB 읽기 복제본 | PostgreSQL 16 (물리 스트리밍 복제, 읽기 전용) | `deploy/db-replica` | 수동 (처음 한 번) |
| 메인 서버 | `backend`, `notification-service` (컨테이너만 나눠 한 서버) | `deploy/main` | 자동 (`Publish backend image`·`Notification service` 성공 시 그 서비스만) |
| 데이터 서버 | `data-service` (수집·원본 저장·정규화·조건 묶음, **1개만**) | `deploy/data` | 자동 (`Data service` 성공 시) |

- 프론트(React)는 S3 + CloudFront로 따로 배포한다 (`frontend.yml`, 아래 "프론트 배포").
- 서버끼리는 **같은 VPC의 사설 IP**로 통신한다.
  - 메인·데이터 → DB: `DB_URL=jdbc:postgresql://<DB 사설 IP>:5432/yon`
  - 메인 → 데이터: `DATA_SERVICE_GRPC_ADDRESS=static://<데이터 사설 IP>:9092` (내부 키 `DATA_SERVICE_INTERNAL_API_KEY`)
- 두 앱 모두 `application_user` 계정·`application` 스키마로 접속한다 (`docs/database-boundaries.md`).
- 수집·정규화는 데이터 서버에서만 돈다. backend의 수집·정규화 실행 플래그는 메인 compose가 `false`로 덮어쓴다.

### 보안 그룹

| 보안 그룹 | 인바운드 |
|---|---|
| `yon-db` | 5432 ← `yon-main`, `yon-data`, `yon-db-replica` 보안 그룹 / 22 ← `yon-main` |
| `yon-main` | `BACKEND_PORT`(8080)·22 ← 0.0.0.0/0 (제공 인벤토리 기준, 접근 제한 개선 예정) |
| `yon-data` | 9092 ← `yon-main` 보안 그룹만 / 22 ← `yon-main` 보안 그룹만 (배포·관리 SSH 모두 메인 서버 경유) |
| `yon-db-replica` | 5432·22 ← `yon-main` 보안 그룹 |

2026-10-28 제공 인벤토리 기준이다. [Terraform 도입 준비와 AWS 담당자 조회 절차](../infra/README.md)에 자원 ID·미확인 항목·후속 PR 순서를 정리했다.

탄력적 IP는 계정당 1개라 메인 서버에만 둔다. 데이터·DB 서버의 공개 IP는 재시작마다 바뀌므로 접속·배포 모두 메인 서버를 거쳐 사설 IP로 한다.

**5432·9092는 0.0.0.0/0으로 열지 않는다.** data-service는 Spring Security가 없다.
한 겹 더: DB 서버 `.env`의 `DB_BIND_IP`, 데이터 서버 `.env`의 `DATA_SERVICE_BIND_IP`에 각 서버의 사설 IP를 넣으면 포트가 사설 IP에만 바인딩된다 (보안 그룹 실수 대비).

### 운영 진입점: CloudFront HTTPS → EC2 HTTP

제공된 구성은 프론트 S3와 backend를 같은 CloudFront 진입점으로 라우팅한다.
`/api/*`·`/oauth2/*`·`/login/oauth2/*`는 backend HTTP 8080 origin으로 가며, viewer HTTP는 HTTPS로 전환한다.
backend origin에는 이미 `X-Forwarded-Proto: https`가 있다. 운영 backend의 전달 헤더 처리 설정·카카오 리다이렉트와 Secure 쿠키는 추가 확인한다.
운영 브라우저의 HTTPS 접속에는 `AUTH_COOKIE_SECURE=true`를 사용한다. `.env.example`의 개발용 false를 그대로 적용하지 않는다.

CloudFront↔origin은 평문 HTTP이며 main의 직접 8080 접근도 현재 허용된 상태다.
접두사 목록을 통한 8080 제한과 추가 origin 보호·origin TLS는 후속 변경으로 검토한다.
22 제한은 기존 GitHub SSH 배포 경로를 대체/검증한 뒤 진행한다.
사용자 HTTPS 진입점과 별도로 main→data gRPC의 TLS 정책은 아래 팀 논의 사항을 따른다.

### SQS·역할 현황

제공 인벤토리에서 사용자/관리자 큐와 DLQ 4개는 `team03-sqs-user`·`team03-sqs-admin` CloudFormation 스택으로 생성됐다.
main에는 `team03-main-sqs`, data에는 `team03-data-sqs` 인스턴스 역할이 연결되어 있다.
정책 본문·큐 속성·애플리케이션 활성화 여부는 2차 조회로 확인한다. Terraform으로 큐를 새로 만들거나 소유권을 이전하지 않는다.

## 워크플로

| 워크플로 | 하는 일 |
|---|---|
| `backend-image.yml` (Publish backend image) | main push → 테스트 → `ghcr.io/<저장소>/backend:sha-<커밋>` 게시 |
| `data-service.yml` (Data service) | main push 중 `data-service/**`·`database/**` 변경 시 → 테스트 → `ghcr.io/<저장소>/data-service:sha-<커밋>` 게시 |
| `notification-service.yml` (Notification service) | main push 중 `notification-service/**` 등 변경 시 → 테스트 → `ghcr.io/<저장소>/notification-service:sha-<커밋>` 게시 |
| `deploy.yml` (Deploy) | 위 셋 중 하나가 **성공하면** 그 서비스를 그 서버에 배포 |
| `frontend.yml` (Frontend) | PR·main push 중 `frontend/**` 변경 시 lint·테스트·빌드, main이면 S3 업로드 + CloudFront 무효화 |

배포 순서 (`deploy.yml`)
1. 서비스의 서버 변수(`EC2_MAIN_HOST` / `EC2_DATA_HOST`)가 비어 있으면 건너뜀
2. GHCR에 그 커밋 이미지가 있는지 확인
3. 그 서버 폴더의 `docker-compose.yml`을 배포 폴더로 복사
4. `deploy.env`의 이미지 줄(`BACKEND_IMAGE` / `DATA_SERVICE_IMAGE` / `NOTIFICATION_IMAGE`)만 바꾸고 `docker compose pull/up -d <서비스>`
5. 약 3분 동안 backend는 HTTP 8080 응답을, data-service는 컨테이너 안에서 인증된 gRPC 9092 읽기 RPC를, notification-service는 컨테이너 안의 8081 포트를 확인한다. 실패하면 로그를 남기고 **이전 이미지로 되돌린 뒤 실패**

- 수동 실행: Actions → Deploy → Run workflow → `service`(backend/data-service/notification-service), `image_tag`(비우면 최신 커밋, 롤백은 `sha-<40자리 커밋 SHA>`)
- 배포는 한 번에 하나씩 (`concurrency: deploy-production`)
- 이미지 보관: 서비스마다 **기동 확인까지 성공한 최근 3개**만 서버에 남긴다 (배포 폴더 `deploy-history/<서비스>`에 기록, 이력에 없는 그 서비스 이미지는 `docker rmi`).
  같은 서버의 다른 서비스 이미지는 건드리지 않는다. 더 오래된 이미지로 롤백하면 GHCR에서 다시 받는다. GHCR 쪽 버전 정리는 별도다
  이력이 없을 때(새 서버·이력 파일 유실)는 방금 성공한 이미지 하나만 남고 그 서비스의 나머지 이미지는 지운다. 최근 3개를 유지하려면 이력을 미리 채운다.
  이력 저장·이미지 삭제 실패는 경고(`::warning::`)만 남기고 배포는 성공으로 둔다. 이력 저장에 실패하면 삭제는 건너뛴다.
  3개는 보관 목표이며 상한이 아니다. 사용 중이라 지우지 못한 이미지는 남으므로 로그의 `서버 이미지 N개`와 경고를 함께 확인한다

### 프론트 배포 (`frontend.yml`)

CloudFront 한 배포가 기본 경로는 S3(프론트), `/api/*`·`/oauth2/*`·`/login/oauth2/*`는 메인 서버 backend로 보낸다.
AWS 인증은 OIDC 역할로 한다 (계정에서 IAM 사용자·액세스 키를 만들 수 없음).

| 준비 | 내용 |
|---|---|
| OIDC 공급자 | IAM → 자격 증명 공급자 `token.actions.githubusercontent.com` (대상 `sts.amazonaws.com`) |
| IAM 역할 | 신뢰 정책: 위 공급자, `aud`=`sts.amazonaws.com`, `sub`=`repo:<조직>@<조직 ID>/<저장소>@<저장소 ID>:ref:refs/heads/main` (이 조직은 ID 포함 형식. 실제 값은 Actions → "OIDC claims check" 수동 실행(`oidc-claims.yml`) 출력으로 확인, 현재 `repo:prgrms-be-devcourse@88020948/NBE11-13-final-Team03@1406626385:ref:refs/heads/main`)<br>권한: 프론트 버킷 `s3:ListBucket`(버킷)·`s3:PutObject`·`s3:DeleteObject`(버킷/*), 그 배포 `cloudfront:CreateInvalidation`만 |
| Actions 변수 | `FRONTEND_BUCKET`, `CLOUDFRONT_DISTRIBUTION_ID`, `FRONTEND_DEPLOY_ROLE_ARN` (키가 아니라 secret 불필요). 선택 `VITE_FIREBASE_*` (공개 설정) |

- 업로드: `assets/`(해시 파일명)는 1년 캐시·삭제 안 함, 나머지는 `no-cache` + 빌드에서 사라진 파일 삭제 → CloudFront `/*` 무효화
- 옛 `assets/`는 지우지 않아 배포마다 조금씩 쌓인다 (한 번에 수백 KB). 나이 기준 S3 수명 주기는 배포가 오래 없으면 현재 파일까지 지우므로 쓰지 않고, 필요할 때 현재 빌드에 없는 파일만 수동 정리한다
- `FRONTEND_BUCKET`이 비어 있으면 배포만 건너뛴다

CloudFront 설정 (콘솔에서 한 번, 워크플로가 바꾸지 않음)

| behavior | 원본 | 설정 |
|---|---|---|
| 기본 (`*`) | S3 프론트 버킷 (OAC) | 기본 루트 객체 `index.html`, viewer-request 함수 `yon-spa-rewrite` (캐시는 파일별 Cache-Control을 따름) |
| `/api/*`, `/oauth2/*`, `/login/oauth2/*` | 메인 서버 backend (HTTP 8080, 사용자 지정 헤더 `X-Forwarded-Proto: https`) | CachingDisabled + 원본 요청 정책 `AllViewer` (쿠키·쿼리·헤더 전체, Host 포함) |

- SPA 딥링크: `BrowserRouter`라 `/mypage` 같은 경로를 새로고침하면 S3에 그 파일이 없다. 기본 behavior에만 붙인 `yon-spa-rewrite`가 확장자 없는 경로를 `/index.html`로 바꾼다 (`login-success.html`, `assets/*.js`처럼 확장자가 있으면 그대로)

  ```js
  function handler(event) {
    var request = event.request;
    if (request.uri.indexOf('.') === -1) {
      request.uri = '/index.html';
    }
    return request;
  }
  ```

- **배포 단위 사용자 지정 오류 응답(403/404 → `/index.html`)은 쓰지 않는다.** 같은 배포의 `/api/*` 응답까지 바뀌어 backend의 403/404 JSON이 `index.html`이 된다
- 인증: `/api/*`에 쿠키가 전달돼야 로그인·`XSRF-TOKEN`이 동작한다 (`AllViewer`)
- 소셜 로그인: Spring이 `redirect_uri`를 요청 Host·`X-Forwarded-Proto`로 만든다. `AllViewer`가 Host(CloudFront 도메인)를, 원본 사용자 지정 헤더가 `https`를 넘기고, backend는 `SERVER_FORWARD_HEADERS_STRATEGY=framework`로 이를 쓴다 → `https://<CloudFront 도메인>/login/oauth2/code/kakao`가 카카오 콘솔 등록 주소와 같아야 한다
  - framework 전략은 헤더를 그대로 믿으므로 8080은 CloudFront에서만 접근하게 막아야 한다 (보안 그룹 CloudFront 접두사 목록)
- 수동 재배포: Actions → Frontend → Run workflow (main)
- 수동 배포(워크플로를 쓸 수 없을 때): `frontend`에서 `npm ci && npm run build` → `dist` 내용물을 버킷에 업로드 → CloudFront 무효화 `/*`

### 서버 준비 순서: DB → 데이터 → 메인

backend에서 수집·정규화를 빼는 PR(#45) 이후에는 데이터 서버가 없으면 새 공고가 들어오지 않는다.
backend를 배포할 때 `EC2_DATA_HOST`가 비어 있으면 경고를 남긴다.

## 처음 한 번 준비할 것

### 1. GHCR
- [ ] 저장소 → Packages → `backend`, `data-service` 각각 Package settings → "Manage Actions access"에 이 저장소가 Read로 있는지 (data-service 패키지는 첫 게시 후 생김)

### 2. EC2 4대 공통
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

### 원본 스냅샷 출처 파티셔닝

수집·정제를 중지한 뒤 원본 전환 SQL → 점검 → 파티셔닝 PR 병합·배포 순서를 지킨다.
backend·행동 로그 전환은 포함하지 않는다. 기존 DB는 SQL 적용 전 병합하지 않는다.
전환 후 옛 이미지로 자동 롤백돼도 DB는 복구되지 않으므로 데이터 서비스를 멈추고 SQL도 되돌려야 한다.
AWS 담당자에게는 [서버별 작업 순서](../docs/raw-partition-aws-runbook.md)를 전달한다.
설계·검증 범위는 [원본 파티셔닝 절차](../docs/raw-external-record-partitioning.md)를 따른다.

### 사용자 행동 로그 월 파티셔닝

backend의 PARTITIONED TABLE 인식 설정을 먼저 배포한 뒤 backend 중지 → 전환 SQL → 점검 → 재기동 순서로 수행한다.
원본 스냅샷 파티셔닝과 독립적인 작업이며 SQL은 자동 실행되지 않는다.
명령·D+1 검증·롤백·월 점검은 [행동 로그 파티셔닝 절차](../docs/user-behavior-events-partitioning.md)를 따른다.

### 백업

정기 백업은 **DB 복제본 서버**의 로컬 디스크에 보관한다. [백업 운영 절차](../docs/db-backup.md)를 따른다.
`deploy/db-replica/backup/`을 `~/yon-db-replica/backup/`에 설치한다. standby만 허용하고 primary는 거부한다.
11:00 KST(서버 가동·수집 종료 확인 전 임시 시각)에 daily 전체 덤프, 일요일에는 같은 덤프의 weekly 사본을 추가한다.
완성 세트 기준 daily 7개·weekly 4개, SHA256·flock·디스크 80% 차단·복제 최신성 점검을 포함한다.
**PITR 미지원, RPO 약 24시간. primary·replica 동시 유실에 대비한 외부 사본은 없다.**
기존 DB/복제 설정은 변경하지 않으며 PR 병합만으로 활성화되지 않는다.
AWS 담당자에게는 [작업 지침](../docs/db-backup-aws-instructions.md)을 전달한다.

## DB 읽기 복제본 (replica)

DB 서버(primary)의 WAL을 물리 스트리밍으로 받아 재생하는 읽기 전용 서버다. 비동기 복제라 primary 커밋을 기다리게 하지 않고, 몇 초 이내 늦을 수 있다.
backend의 읽기 전용 트랜잭션만 이쪽을 쓰고, 쓰기·data-service·notification-service는 primary만 쓴다 (라우팅은 backend 설정).
물리 복제라 파티션 생성·분리 같은 DDL과 계정도 그대로 따라온다. 파티션 관리는 primary에서만 한다.

- EC2: `yon-db-replica` (t3.small, DB 서버와 같은 서브넷), 공통 준비는 위 서버 준비 절차를 따른다
- 보안 그룹 `yon-db-replica`: 5432 ← `yon-main` 보안 그룹, 22 ← `yon-main` 보안 그룹 (노트북에서는 ProxyJump로 접속)
- `yon-db` 보안 그룹에 5432 ← `yon-db-replica` 보안 그룹 추가

### 1. primary 준비 (DB 서버, 처음 한 번)

```bash
cd ~/yon-db
docker compose exec postgres-db psql -U postgres -d postgres
```
```text
CREATE ROLE replicator WITH REPLICATION LOGIN;
\password replicator
SELECT pg_create_physical_replication_slot('yon_replica_1');
ALTER SYSTEM SET max_slot_wal_keep_size = '2GB';
SELECT pg_reload_conf();
SELECT slot_name, active FROM pg_replication_slots;
\q
```
**마지막 조회로 슬롯이 만들어졌는지 확인한다** (`yon_replica_1 | f`). 슬롯이 없으면 2번의 복사가 끝까지 진행된 뒤 마지막에
`replication slot "yon_replica_1" does not exist`로 실패하고 복사한 데이터를 지운다. `\password`에서 빈 값을 넣으면 비밀번호가 지워지니 다시 입력한다.
복제 슬롯은 복제본이 잠깐 끊겨도 필요한 WAL을 남긴다. 복제본이 오래 죽어 있으면 primary 디스크가 차지 않게 2GB에서 슬롯을 끊는다 (그때는 3번부터 다시 한다).

복제 접속 허용 (복제본 사설 IP만) 후 설정 다시 읽기:
```bash
docker compose exec -T postgres-db sh -c 'echo "host replication replicator <복제본 사설 IP>/32 scram-sha-256" >> "$PGDATA/pg_hba.conf"'
docker compose exec -T postgres-db psql -U postgres -c 'SELECT pg_reload_conf()'
```

### 2. 복제본 띄우기 (복제본 서버)

```bash
mkdir -p ~/yon-db-replica && cd ~/yon-db-replica
```
`deploy/db-replica/docker-compose.yml`과 `.env`(`DB_BIND_IP`=이 서버의 사설 IP, `chmod 600`)를 둔다. 빈 볼륨으로 띄우면 새 DB가 만들어지므로, 컨테이너만 만들고 볼륨을 primary 복사본으로 채운 뒤 띄운다.

```bash
docker compose create
docker run --rm -it -v yon-db-replica_postgres_data:/var/lib/postgresql/data postgres:16-alpine sh -c \
  'chown postgres:postgres "$PGDATA" && chmod 700 "$PGDATA" && su-exec postgres pg_basebackup -h <DB 서버 사설 IP> -U replicator -D "$PGDATA" -Fp -Xs -P -R -S yon_replica_1 -W'
docker compose up -d
```
`-W`가 replicator 비밀번호를 묻는다. 실패하면 pg_basebackup이 볼륨을 비우므로 원인을 고친 뒤 같은 `docker run` 명령만 다시 실행한다 (`docker compose create`는 다시 하지 않아도 된다). `-R`이 `standby.signal`과 접속 정보(`postgresql.auto.conf`, 비밀번호 포함, 권한 700 데이터 폴더 안)를 남긴다.

### 3. 확인

primary(DB 서버):
```bash
docker compose exec -T postgres-db psql -U postgres -Atc "select client_addr, state, sync_state, replay_lag from pg_stat_replication; select slot_name, active from pg_replication_slots"
```
`streaming|async`, 슬롯 `active=t`. 복제본에서 `select pg_is_in_recovery()`가 `t`이고 쓰기는 `read-only transaction` 오류가 나야 정상이다.

### 장애 때 (수동)
- primary가 죽으면: 복제본에서 `docker compose exec -u postgres postgres-replica pg_ctl promote -D /var/lib/postgresql/data` → 메인·데이터 서버 `.env`의 `DB_URL`을 복제본 사설 IP로 바꾸고 재시작. 옛 primary는 버리고 새 복제본을 2번부터 다시 만든다.
- 복제본이 죽으면: backend 읽기만 영향. 복제본 주소를 primary로 바꾸거나 복제본을 다시 띄운다. 슬롯이 끊겼으면(`active=f`가 오래 지속, 디스크 경고) `SELECT pg_drop_replication_slot('yon_replica_1')` 후 1·2번을 다시 한다.

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
scp database/scripts/notification-{inbox,user-api,session-end,withdrawal,delivery,admin}.sql ubuntu@<DB 서버>:~/yon-db/sql/
```
DB 서버에서:
```bash
cd ~/yon-db
for f in inbox user-api session-end withdrawal delivery admin; do
  { echo 'SET ROLE notification_user;'; echo 'SET search_path TO notification, pg_catalog;'; cat sql/notification-$f.sql; } | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1 || break
done
```

2. 메인 서버 배포 폴더에 `notification.env` 작성 (`deploy/main/notification.env.example`, `chmod 600`). backend `.env`와 파일을 나눠 `application_user` 비밀번호가 알림 컨테이너에 들어가지 않게 한다.
3. Actions → Deploy → `notification-service`로 배포한다 (또는 `notification-service` 변경이 main에 병합되면 자동). 이미지는 알림 서버 변경이 있었던 커밋에만 있으므로 수동 배포 때는 `image_tag`에 그 커밋을 넣는다.

수신·사용자 API·푸시·SQS 기능은 모두 기본 꺼짐이다. 켜는 시점과 값(`NOTIFICATION_INTERNAL_API_KEY` 등)은 알림 담당이 정하고, backend `.env`의 `NOTIFICATION_PUBLISHER_ENABLED`·`NOTIFICATION_FACADE_ENABLED`와 함께 켠다.

기능을 켜기 전에 추가로 적용할 SQL (위 1번과 같은 방식, `notification_user`, 알림 서버를 멈춘 상태에서)
- 푸시 슬롯 모드(`NOTIFICATION_PUSH_SLOTS_ENABLED`): `notification-push-slots.sql`. 푸시 만료 분리 이전의 delivery SQL을 적용했던 기존 DB는 그 전에 `notification-push-expiry.sql` (새 DB는 delivery SQL에 포함)
- 새 적합 알림 우선순위(#80 머지 후): `notification-new-match-priority.sql`
- 로그아웃 시 푸시 Endpoint 정리(세션 바인딩, `docs/notification-session-end.md`): `notification-session-end.sql`. 알림 서버를 먼저 올리고 backend를 배포한다
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
