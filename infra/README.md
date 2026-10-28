# AWS 인벤토리와 Terraform 도입 준비

이번 단계는 기존 구성 확인·2차 조회 도구·Terraform 검증 환경을 준비한다.
AWS 자원 생성·변경·import·삭제를 실행하지 않는다. 준비 PR 병합으로 운영 설정이 바뀌지 않는다.

## 확인 기준

- 기준 코드: main `6084e0a`, 2026-10-28 운영자가 제공한 CloudShell 내보내기.
- 검토 가능한 기준본: [baseline-2026-10-28.json](inventory/baseline-2026-10-28.json).
  계정/리전·자원 ID·보안 그룹·암호화·IMDS 상태와 원본 파일 SHA256을 보존했다. 실시간 조회 결과가 아니다.
- 계정 `174170816230`, 리전 `ap-northeast-2`, 전용 VPC 안 EC2 4대(t3.small).
- main/data 인스턴스에 SQS 역할이 연결되어 있고, 큐 4개는 CloudFormation 스택 2개가 관리한다.
- CloudFront의 backend origin은 HTTP 8080이며 **이미 `X-Forwarded-Proto: https`를 추가한다.**
  요청 정책 변경부터 할 필요는 없다. 운영의 `SERVER_FORWARD_HEADERS_STRATEGY`, OAuth 리다이렉트 및 `AUTH_COOKIE_SECURE`는 별도 확인한다.
- 관리 후보 보안 그룹 4개의 인바운드 소스 규칙은 **10개**, 아웃바운드는 4개다.
  default 그룹의 자기 참조 인바운드 1개와 아웃바운드 1개는 가져오지 않는다. 실제 규칙 ID는 2차 조회가 필요하다.
- main의 8080·22가 전체 공개이고, EBS 4개는 미암호화 상태로 제공됐다.
- 서버 09:00~18:00 KST 가동은 사용자 전달값이다. 스케줄 소유자·정의·시간대는 미확인이다.

## AWS 담당자: 2차 조회

AWS CLI 2와 Python 3.9 이상이 있는 CloudShell에서 전달받은 `infra/` 폴더를 푼 후 실행한다.
스크립트는 로그인한 계정을 먼저 대조한다. 다른 계정/인증 실패면 자원 조회를 시작하지 않는다.

```bash
umask 077
INVENTORY_DIR="$HOME/yon-inventory-2-$(date -u +%Y%m%dT%H%M%SZ)"
python3 infra/scripts/collect_inventory.py --output "$INVENTORY_DIR"
```

기존 디렉터리는 덮어쓰지 않는다. `inventory.json` 하나에 조회별 `status`, 성공 `data` 또는 실패 `error_code`를 저장한다.
CloudFront LIVE Function 코드는 같은 디렉터리의 `cf-function-*.js`로 따로 받는다.
조회가 일부 실패해도 다른 조회를 계속하며, `complete=false`와 종료 코드 1로 표시한다.
권한 거부·시간 초과·자원 미발견을 자원 부재로 해석하지 않는다. 중단 때에도 수집한 JSON은 보존한다.
AWS CLI의 자동 페이지 조회를 유지하며, get/list/describe 명령만 실행한다.

조회 범위:

- 대상 VPC/서브넷/라우팅/IGW, EC2 4대/연결 볼륨/EIP/보안 그룹과 규칙 ID, 종료 보호·EBS 기본 암호화.
- 지정 역할 5개의 신뢰 정책·프로파일·inline 정책·연결된 관리형 정책의 현재 버전 본문과 GitHub OIDC 공급자.
  `devcos-*` 역할 삭제 검토는 후속 단계다.
- 프론트 S3의 정책/공개 차단/버전 관리/암호화/소유권, yon-backend origin이 있는 CloudFront 배포 ID와 전체 설정/OAC/연결 Function.
- 지정 SQS 4개 속성과 CloudFormation 스택 2개/소속 자원.
- 서울 리전 Scheduler의 상세 스케줄, 기본·커스텀 EventBridge 버스별 규칙/대상, 알람, 계정 예산·Route53 영역.
  다른 리전·중앙 계정·교육 기관의 외부 자동화는 이 조회로 발견되지 않을 수 있다.

결과에는 내부 구성·정책·origin 헤더 값·알림 대상 등이 포함될 수 있다. **원본 JSON/JS/ZIP과 Terraform state/plan을 Git에 올리지 않는다.**
검토 후 자원 ID와 필요한 속성만 정리해 `infra/inventory/`에 추가한다. 수집 디렉터리의 권한은 POSIX 기준 0700, 파일은 0600이다.
AWS 담당자는 원본을 승인된 비공개 경로로 전달하고, `complete=false`이면 오류 항목과 추가 권한/재조회 필요 여부를 함께 알려 준다.

함께 확인할 사항:

1. 서버 기동·정지 스케줄의 실제 관리 주체와 시간대, 일요일 가동 여부.
2. 프론트 배포 워크플로 위치, 실제 OIDC 역할·토큰 `sub`. 기존 두 역할은 목적별 권한 확인 전 통합·삭제하지 않는다.
3. CloudFront 주소에서 로그인·토큰 갱신이 정상인지, HTTPS 전달 헤더 처리와 Secure 쿠키 설정.
4. 백업 타이머의 실제 다음 실행 시각과 별도 DB 복원 시험 결과. S3 백업 재도입은 이번 범위에 포함하지 않는다.

## 코드 담당자: Terraform 검증

Terraform **1.17.0**, AWS provider는 `.terraform.lock.hcl`의 검증된 버전을 사용한다.
현재 `.tf`에는 버전/공급자 선언만 있다. 관리 자원·data·import·provider 인증 설정은 다음 단계에서 추가한다.

```bash
python3 -m unittest discover -s infra/tests -v
terraform -chdir=infra/terraform fmt -check
terraform -chdir=infra/terraform init -backend=false -input=false -lockfile=readonly
terraform -chdir=infra/terraform validate
terraform -chdir=infra/terraform plan -input=false -refresh=false -detailed-exitcode
```

CI도 같은 검사를 AWS 자격증명/OIDC 없이 실행한다. 빈 계획의 종료 코드 0은 **AWS 접근·실제 구성 일치·복구 가능성 검증이 아니다.**
공급자를 내려받을 네트워크는 필요하다. Terraform 파일과 provider lock 파일은 커밋하되 `.terraform/`·state·plan·실제 backend 설정은 제외한다.

## 원격 상태·권한 준비 — 다음 단계 진입 조건

기존에 공유된 계정의 추가 S3 버킷 생성 제한이 있다. 상태 저장용 버킷을 임의 생성하거나 프론트 버킷을 공유하지 않는다.
AWS 담당자/팀이 추가 비공개 버킷 승인 또는 별도 원격 backend를 결정해야 한다. 버킷 이름은 승인된 실제 이름을 사용한다.
로컬 state는 이 빈 준비 구성의 검증에만 사용하며 운영 자원 import는 원격 상태 결정 뒤 진행한다.

S3가 승인되면 아래 예시를 사용한다. 실제 생성과 접근 검증은 AWS 담당자 작업이다.

```bash
cd infra/terraform
cp backend.tf.example backend.tf
cp backend.hcl.example backend.hcl
# backend.hcl의 승인된 버킷 이름을 채운다. 비밀 키는 넣지 않는다.
terraform init -backend-config=backend.hcl
```

상태 버킷은 비공개·암호화·버전 관리를 적용하고 `use_lockfile=true`로 잠근다. 필요하면 KMS 권한도 확인한다.
새 PR에서 활성 backend 설정과 운영 절차를 함께 확정한다. backend.tf의 비밀값 없는 선언은 추적 대상으로 전환하고, 실제 backend.hcl은 계속 제외한다.
현재 CI의 로컬 빈 계획도 해당 PR에서 원격 plan 절차로 바꾼다.

PR용 역할은 **AWS 인프라 조회 전용**으로 별도 준비한다. 기존 배포 역할에 ReadOnly 정책을 더하는 것은 쓰기 권한을 제거하지 않는다.
선택한 backend의 state 읽기와 잠금 파일 생성·삭제 권한은 별도로 필요하다. import 실행자는 state 쓰기도 필요하다.
현재 main 전용 OIDC `sub`를 PR에 그대로 쓰지 말고 실제 PR 토큰 subject·저장소·audience 조건을 확인한다.
외부 fork PR에는 AWS 권한을 주지 않는다. `pull_request_target`으로 외부 코드를 실행하는 구조는 사용하지 않는다.
원격 plan 결과에는 민감한 값이 포함될 수 있어 Git/공개 PR 댓글에 원문을 자동 게시하지 않는다.

## 후속 PR 순서와 완료 기준

| 단계 | 작업 | 완료 기준 |
| --- | --- | --- |
| 이번 준비 PR | 기준 인벤토리·읽기 수집기·Terraform 골격·검증 CI·담당자 지침 | 모의 테스트, fmt/init/validate/빈 plan 통과 |
| 원격 상태 준비 | 승인된 저장소·상태 잠금·조회 역할·OIDC 조건 | AWS 담당자 연결/권한 확인, 2차 조회 오류 분류 완료 |
| 네트워크 가져오기 | VPC·서브넷·IGW·라우팅/연결·그룹 4개·인바운드 10/아웃바운드 4 규칙 | import 계획의 생성·변경·삭제 0 → state에 import 적용 → 다시 plan 변경 0 |
| 첫 운영 변경 | 8080의 소스를 CloudFront origin-facing 접두사 목록으로 제한 | CloudFront 경유 API/OAuth 정상, 직접 접속 차단, 롤백 방법 확인 |
| 관리 범위 확대 | IAM·프론트·EC2/EIP/키 페어를 묶음별 import | 묶음별 실제 변경 0, 삭제/교체 방지 검토 |
| 선택 개선 | SSM/22 제한, EBS 암호화, 스케줄·모니터링, 도메인/origin TLS | 항목별 별도 PR·운영 검증 |

CloudFront 접두사 목록은 SG 규칙 55개 분량을 사용하며 특정 팀 배포만 식별하지 않는다. 한도와 추가 origin 식별 방식을 검토한다.
현재 GitHub 배포는 SSH이므로 22번을 운영자 IP로만 제한하기 전에 배포 경로(SSM 등)를 먼저 확보한다.
EBS 기본 암호화는 기존 볼륨을 암호화하지 않는다. 기존 볼륨 교체는 복원 검증·실측 중단 시간·롤백을 별도 계획한다.
SQS는 CloudFormation 소유를 유지한다. 이전을 선택하면 운영 중 이중 관리를 피하는 소유권 이전 절차부터 별도 검토한다.
Terraform으로 인프라를 가져와도 DB 데이터·.env·Compose·비밀값·백업 복원이 자동 재현되는 것은 아니다.
`prevent_destroy`는 운영 백업이나 AWS 콘솔 종료 보호를 대신하지 않는다.

## 근거

- [S3 backend와 상태 잠금](https://developer.hashicorp.com/terraform/language/backend/s3)
- [AWS CloudFront 접두사 목록](https://aws.amazon.com/blogs/networking-and-content-delivery/limit-access-to-your-origins-using-the-aws-managed-prefix-list-for-amazon-cloudfront/)
- [Spring 전달 헤더 처리](https://docs.spring.io/spring/reference/web/webmvc/filters.html#filters-forwarded-headers)
- [ACM 내보내기 지원 인증서](https://docs.aws.amazon.com/acm/latest/userguide/acm-exportable-certificates.html): EC2 origin TLS에 ACM을 전혀 쓸 수 없다는 기존 계획의 단정은 제외한다. 비용·갱신·배포 방식은 별도 결정한다.
