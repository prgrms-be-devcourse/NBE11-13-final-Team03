# 배포 (GitHub Actions → GHCR → EC2 docker compose)

이미지 빌드와 배포는 워크플로 두 개로 나뉜다.

1. `.github/workflows/backend-image.yml` (Publish backend image): main push 시 테스트 → `bootJar` → `backend/Dockerfile`로 이미지 빌드 → `ghcr.io/<저장소 소문자>/backend:sha-<커밋 SHA>`로 push
2. `.github/workflows/deploy.yml` (Deploy backend): 1번이 **성공하면** 이어서 실행
   1. GHCR에 그 커밋 이미지가 있는지 확인
   2. `deploy/docker-compose.prod.yml`을 EC2 배포 폴더로 복사 (SSH)
   3. EC2에서 이 실행의 `GITHUB_TOKEN`으로 GHCR 로그인 → `deploy.env`에 이미지 기록 → `docker compose pull` → 로그아웃 → `up -d`
   4. `http://localhost:${BACKEND_PORT}/`가 응답하는지 최대 3분 확인. 응답이 없으면 로그를 남기고 **이전 이미지로 되돌린 뒤 실패**

실행 조건
- 자동: main push → 이미지 게시 성공 → 배포. 이미지 게시(테스트 포함)가 실패하면 배포하지 않는다
- 수동: Actions → Deploy backend → Run workflow
  - `image_tag` 비움: 실행한 브랜치(main) 최신 커밋 이미지로 배포
  - `image_tag` 입력: 그 이미지로 배포 (롤백용, 형식 `sha-<40자리 커밋 SHA>`. GHCR에 있어야 함)
- 배포는 한 번에 하나씩 (`concurrency: deploy-production`)
- 저장소 변수 `EC2_HOST`가 비어 있으면 배포 작업을 건너뛴다 (EC2 준비 전 main push가 실패로 표시되지 않게)

## 처음 한 번 준비할 것

### 1. GHCR
- 별도 준비 없음. 패키지가 저장소에 연결되어 있으면(이미지 라벨 `org.opencontainers.image.source`) 저장소 워크플로의 `GITHUB_TOKEN`으로 비공개여도 받을 수 있다.
- [ ] 첫 배포 전 확인: 저장소 → Packages → backend → Package settings → "Manage Actions access"에 이 저장소가 있는지 (없으면 Read로 추가)

### 2. EC2
- [ ] 인스턴스 (팀 서버: Ubuntu, t3.small, x86_64). 이미지 플랫폼 기본값이 `linux/amd64`라 Arm 인스턴스는 안 됨 (Arm이면 저장소 변수 `IMAGE_PLATFORM`을 바꿔야 함)
- [ ] 탄력적 IP 연결 (껐다 켜도 IP 유지)
- [ ] 보안 그룹
  - 22: GitHub Actions에서 SSH 접속 (아래 "주의" 참고)
  - `BACKEND_PORT`(기본 8080): 서비스 공개 범위에 맞게
  - 5432는 열지 않는다 (DB는 compose 내부 네트워크로만 접근)
- [ ] Docker, compose 플러그인, 스왑, 시간대 (Ubuntu)

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
(`usermod` 후에는 다시 로그인해야 적용된다)

- [ ] 배포 폴더와 `.env` 만들기 (기본 경로 `/home/<EC2_USER>/yon`)

```bash
mkdir -p ~/yon && cd ~/yon
nano .env
chmod 600 .env
```
(`.env` 내용은 저장소의 `backend/deploy/.env.example`을 붙여 넣고 값 채우기)

### 3. GitHub 설정 (Settings → Secrets and variables → Actions)

| 종류 | 이름 | 값 |
|---|---|---|
| Variable | `EC2_HOST` | EC2 탄력적 IP 또는 도메인 |
| Variable | `EC2_USER` | `ubuntu` |
| Variable | `EC2_DEPLOY_DIR` | (선택) 기본값 `/home/<EC2_USER>/yon` |
| Secret | `EC2_SSH_KEY` | EC2 접속용 **배포 전용** 개인키 전체 (`-----BEGIN ... KEY-----`부터 끝까지) |

## 운영 중 자주 쓰는 명령 (EC2 배포 폴더에서)

```bash
c() { docker compose -f docker-compose.prod.yml --env-file .env --env-file deploy.env "$@"; }
c ps
c logs -f --tail 200 backend
c restart backend
cat deploy.env deploy.env.prev
```
(`restart`는 `.env`를 바꾼 뒤. EC2에서 직접 `pull`하려면 GHCR 로그인이 필요하다. 평소에는 Actions 수동 실행으로 배포·롤백한다)

## 주의
- **SSH 22번 포트**: GitHub 호스팅 러너는 IP 대역이 넓고 자주 바뀐다. 22번을 전체에 열면 키 기반 접속만 허용되더라도 공격 대상이 된다. 비밀번호 로그인 금지, 배포 전용 키 사용을 지키고, 나중에 SSM 접속으로 바꾸는 것을 검토한다.
- **DB 백업**: compose 안 PostgreSQL은 도커 볼륨(`yon_postgres_data`)에 저장된다. 인스턴스를 지우면 함께 사라진다. 운영 데이터가 생기기 전에 백업 또는 RDS 이전을 정한다.
- **`ddl-auto: update`**: 배포 때 스키마가 자동으로 바뀌지만 enum CHECK 제약은 갱신하지 않는다. enum 값이 늘어나는 배포는 제약 재생성 SQL을 먼저 실행한다 (기술 부채로 기록됨).
- **테스트**: 테스트는 이미지 게시 워크플로가 돌린다. 테스트가 실패하면 이미지도 배포도 없다.
