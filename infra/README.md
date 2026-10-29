# AWS 인벤토리와 Terraform 기존 인프라 관리

PR #103에서 네트워크·IAM·프론트·EC2의 기존 자원 import를 한 묶음으로 제공한다.
담당자는 [전체 import 실행 지침](adoption.md)의 통합 조회 정책과 일괄 실행 명령을 사용한다.
코드·CI만으로 운영 import나 AWS 자원 변경을 실행하지 않는다. 실행 시 운영값을 비공개로 수집해 계획을 검증하고 상태에 등록한다.
다만 기존 `backend-image.yml`은 main의 모든 push에 실행되고, 성공하면 `deploy.yml`이 backend를 배포한다.
따라서 인프라 PR도 병합 시 기존 backend 빌드·재배포가 이어질 수 있다. 병합 시점은 운영 가동 시간과 맞춘다.

## 확인 기준

- 기준 코드: main `6084e0a`, 2026-10-28 운영자가 제공한 CloudShell 내보내기.
- 검토 가능한 기준본: [baseline-2026-10-28.json](inventory/baseline-2026-10-28.json).
  계정/리전·자원 ID·보안 그룹·암호화·IMDS 상태와 원본 파일 SHA256을 보존했다. 실시간 조회 결과가 아니다.
- 계정 `174170816230`, 리전 `ap-northeast-2`, 전용 VPC 안 EC2 4대(t3.small).
- main/data 인스턴스에 SQS 역할이 연결되어 있고, 큐 4개는 CloudFormation 스택 2개가 관리한다.
- CloudFront의 backend origin은 HTTP 8080이며 **이미 `X-Forwarded-Proto: https`를 추가한다.**
  요청 정책 변경부터 할 필요는 없다. 운영의 `SERVER_FORWARD_HEADERS_STRATEGY`, OAuth 리다이렉트 및 `AUTH_COOKIE_SECURE`는 별도 확인한다.
- 관리 후보 보안 그룹 4개의 인바운드 소스 규칙은 **10개**, 아웃바운드는 4개다.
  default 그룹의 자기 참조 인바운드 1개와 아웃바운드 1개는 가져오지 않는다.
- 2026-10-28 03:48 UTC의 network 범위 2차 조회는 `complete=true`로 제공됐다.
  [network-2026-10-28.json](inventory/network-2026-10-28.json)에 라우팅·연결·규칙 ID와 원본 SHA256을 정리했다. 원본 파일은 커밋하지 않는다.
- main의 8080·22가 전체 공개이고, EBS 4개는 미암호화 상태로 제공됐다.
- 서버 09:00 기동·18:00 정지(KST)는 운영 담당자가 `last -x reboot shutdown`의 10-26·10-27 기록으로 확인한 관측값이다.
  스케줄 소유자·정의·스케줄의 시간대 설정은 여전히 미확인이다.
- 10-28 운영 후속 확인: `team03-github-actions-deploy`는 삭제돼 조회/import에서 제외했다.
  프론트 배포 역할의 OIDC `sub`는 조직·저장소 ID를 포함한 형식으로 변경됐으며 실행 시 현재 신뢰 정책을 재조회한다.
  기준본은 당시 내보내기 이력으로 보존하며 현재 운영값과 구분한다.

## AWS 담당자: 2차 조회

**전체 import는 [adoption.md](adoption.md)를 사용한다.** 아래 기존 조회 방법은 사전 확인·후속 감사용이며 단계마다 담당자에게 따로 실행을 요청할 필요가 없다.
새 `--scope import`는 채택 대상 전체만 수집하고, 기존 `--scope all`은 후속 감사 범위까지 수집한다.

CloudShell은 필수가 아니다. AWS CLI 2와 Python 3.9 이상이 있는 로컬 PC나 권한이 준비된 EC2에서도 같은 수집기를 실행한다.
전달받은 `infra/` 폴더를 푼 루트에서 실행한다.
스크립트는 로그인한 계정을 먼저 대조한다. 다른 계정/인증 실패면 자원 조회를 시작하지 않는다.

### CloudShell 없이 Windows에서 실행

AWS CLI 2.32.0 이상을 [공식 설치 안내](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)에 따라 준비한다.
웹 콘솔 로그인이 가능한 IAM 사용자/역할은 `aws login`의 브라우저 인증으로 임시 자격증명을 받을 수 있다.
해당 identity에 `SignInLocalDevelopmentAccess` 권한과 실제 인프라 조회 권한이 필요하다. 로그인 가능 여부와 조회 권한은 별개다.
계정 선택·로그인·동의는 사용자가 브라우저에서 직접 완료한다. 새 장기 Access Key를 발급하거나 채팅에 자격증명을 붙여 넣지 않는다.

PowerShell에서:

```powershell
aws --version
aws login --profile yon-inventory --region ap-northeast-2
if ($LASTEXITCODE -ne 0) { throw 'AWS 브라우저 로그인 실패: 권한과 로그인 방식을 확인하세요.' }
$env:AWS_PROFILE = 'yon-inventory'
aws sts get-caller-identity --query Account --output text --no-cli-pager
if ($LASTEXITCODE -ne 0) { throw 'AWS 계정 확인 실패' }
$inventoryOutput = Join-Path $env:USERPROFILE ('yon-inventory-2-' + (Get-Date -Format 'yyyyMMddTHHmmss'))
py -3 infra/scripts/collect_inventory.py --output $inventoryOutput
```

출력 계정은 `174170816230`이어야 한다. 수집기도 이를 다시 검사한다.
로그인에서 권한 거부가 나면 관리자가 로그인 기능 사용 권한을 확인해야 하며, 수집 결과의 AccessDenied는 별도 조회 권한 확인이 필요하다.
IAM Identity Center를 사용하는 계정은 위 로그인 대신 `aws configure sso --profile yon-inventory` 후 `aws sso login --profile yon-inventory`를 사용한다.
이미 승인된 CLI 프로필이 있으면 해당 프로필을 AWS_PROFILE로 선택하면 된다.
수집 후 이 로그인 세션을 닫으려면 `aws logout --profile yon-inventory`를 실행한다.
Windows 출력 폴더는 사용자 전용 위치에 보관하고 공유 드라이브에 두지 않는다. 아래 POSIX 파일 권한 설명은 Windows ACL을 대신하지 않는다.

### Linux/macOS 또는 CloudShell에서 실행

웹 콘솔 로그인이 가능한 로컬 PC에서는 먼저 `aws login --profile yon-inventory --region ap-northeast-2`를 완료하고 `export AWS_PROFILE=yon-inventory`를 설정한다.
CloudShell이나 조회 권한이 준비된 EC2 역할을 사용한다면 해당 환경의 기존 인증을 사용한다.

```bash
umask 077
INVENTORY_DIR="$HOME/yon-inventory-2-$(date -u +%Y%m%dT%H%M%SZ)"
python3 infra/scripts/collect_inventory.py --output "$INVENTORY_DIR"
```

EC2는 연결된 역할이 인프라 조회 권한을 갖는지 먼저 확인한다. 기존 main/data의 SQS 역할만으로 모든 조회가 가능하다고 가정하지 않는다.
SSH 호스트 키가 달라지면 검증 없이 known_hosts를 지우거나 확인을 끄지 않고, AWS 담당자가 신뢰할 수 있는 경로에서 현재 서버 키를 확인한다.

### EC2에서 네트워크 조회 권한만 임시로 준비하는 경우

사용자 제공 실행 결과(2026-10-28)에서 main의 AWS CLI 2.35.21과 `team03-main-sqs` 역할 인증은 정상이며,
`ec2:DescribeVpcs`는 identity policy에 허용이 없어 UnauthorizedOperation으로 거부됐다.
SSH 연결이나 다른 로그인 방법으로 해당 권한 제한을 해소할 수는 없다.

전체 IAM/S3/CloudFront 권한을 앱 역할에 추가하는 대신, 다음 네트워크 단계에 필요한 EC2 조회만 임시 허용하는 선택지를 제공한다.
실제 역할 정책 변경은 IAM 권한을 가진 담당자가 검토·적용한다. 코드/PR만으로 권한을 추가하지 않는다.

1. AWS 콘솔 → IAM → 역할 → **team03-main-sqs** → 권한 → 권한 추가 → 인라인 정책 생성 → JSON.
2. [network-inventory-read.json](iam/network-inventory-read.json)을 붙여 넣고 `YONNetworkInventoryReadTemporary`라는 이름으로 추가한다.
   기존 SQS 정책·역할 신뢰 관계·EC2 인스턴스 프로파일은 교체하지 않는다.
3. 권한 반영 후 EC2에서 이전 DescribeVpcs 명령이 성공하는지 확인한다. 앱/DB 재시작은 필요하지 않다.
4. 전달본을 푼 루트에서 다음을 실행한다. EC2 역할 인증을 사용하므로 `aws login`이 필요하지 않다.

```bash
umask 077
INVENTORY_DIR="$HOME/yon-network-inventory-$(date -u +%Y%m%dT%H%M%SZ)"
python3 infra/scripts/collect_inventory.py --scope network --output "$INVENTORY_DIR"
```

`scope=network`의 `complete=true`는 지정한 EC2 조회가 성공했다는 뜻이다. 전체 AWS 구성 수집 완료가 아니다.
이 모드는 STS 계정 확인과 서울 리전 EC2 조회만 수행하며 IAM·S3·CloudFront·SQS 등은 호출하지 않는다.
정책은 읽기 작업 12개만 허용하며 생성·변경·삭제 권한은 없다. 목록 조회에는 Resource `*`와 서울 리전 조건을 사용한다.
인스턴스 속성 조회는 대상 4대의 ARN으로 제한한다. 목록 조회의 특정 VPC 제한은 수집기 필터다. 이 범위가 허용되지 않으면 콘솔에서 필요한 ID만 제공한다.
조회 후 결과를 비공개로 전달하고 담당자가 **추가한 임시 인라인 정책만** 제거한다. 기존 SQS 정책은 유지한다.
정책 추가 후에도 거부되면 권한 경계·조직 정책 등은 관리자에게 확인한다. 전체 서비스 조회 권한은 별도로 확인한다.

### 수집 결과 확인

기존 디렉터리는 덮어쓰지 않는다. `inventory.json` 하나에 조회별 `status`, 성공 `data` 또는 실패 `error_code`를 저장한다.
CloudFront LIVE Function 코드는 같은 디렉터리의 `cf-function-*.js`로 따로 받는다.
조회가 일부 실패해도 다른 조회를 계속하며, `complete=false`와 종료 코드 1로 표시한다.
권한 거부·시간 초과·자원 미발견을 자원 부재로 해석하지 않는다. 중단 때에도 수집한 JSON은 보존한다.
AWS CLI의 자동 페이지 조회를 유지하며, get/list/describe 명령만 실행한다.

조회 범위:

- 대상 VPC/서브넷/라우팅/IGW, EC2 4대/연결 볼륨/EIP/보안 그룹과 규칙 ID, 종료 보호·EBS 기본 암호화.
- 지정 역할 4개의 신뢰 정책·프로파일·inline 정책·연결된 관리형 정책의 현재 버전 본문과 GitHub OIDC 공급자(전체 감사 범위).
  실제 import 범위는 main/data SQS·프론트 배포 역할 3개이며, `team03-ec2-ecr-read`는 감사 조회만 한다.
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
VPC·IAM·서버·S3/CloudFront를 실제 ID/이름으로 가져오며 계정·리전을 고정한다.
8080/22의 현재 접근 정책을 유지한다. CloudFront 소스 제한은 별도 PR이다.

```bash
python3 -m unittest discover -s infra/tests -v
terraform -chdir=infra/terraform fmt -check -recursive
terraform -chdir=infra/terraform init -input=false -lockfile=readonly
terraform -chdir=infra/terraform validate
terraform -chdir=infra/terraform test -no-color
```

CI도 같은 검사를 AWS 자격증명/OIDC 없이 실행한다. 테스트는 합성 입력과 mock provider import 응답을 사용한다.
추가 검사에서 합성 대상 56개 전부의 import ID와 AWS 변경 0을 대조한다. 운영 총 개수는 실제 IAM 정책/버킷 설정에 따라 정해진다.
이 결과는 **실제 AWS 조회·무변경 계획·운영 상태 등록 완료를 의미하지 않는다.**
공급자를 내려받을 네트워크는 필요하다. Terraform 파일과 provider lock 파일은 커밋하되 `.terraform/`·state·plan·실제 backend 설정은 제외한다.

## 상태 파일 — 추가 S3 없이 로컬 보관

추가 S3 버킷을 만들 수 없다는 팀 제약에 따라 local backend를 사용한다. 프론트 버킷도 공유하지 않는다.
원격 상태 저장소 생성은 네트워크 단계의 선행 조건에서 제외한다.
운영 실행자는 AWS 담당자 한 명으로 정하고, 지정한 PC 또는 EC2 한 곳의 절대 경로에 상태 파일을 보관한다.
여러 PC·checkout에서 각자 빈 state로 운영 명령을 실행하지 않는다. 같은 경로를 사용해야 로컬 잠금도 공유된다.

지정 실행 환경이 Ubuntu EC2인 경우 준비 예시:

```bash
umask 077
TF_STATE_DIR="$HOME/yon-terraform-state"
mkdir -p "$TF_STATE_DIR"
chmod 700 "$TF_STATE_DIR"
terraform -chdir=infra/terraform init -input=false -lockfile=readonly \
  -backend-config="path=$TF_STATE_DIR/terraform.tfstate"
```

Windows에서는 사용자 전용 폴더의 절대 경로를 같은 `path` 설정에 사용한다.
`backend.hcl.example`을 복사해 실제 경로를 채운 뒤 `-backend-config=backend.hcl`로 초기화할 수도 있다.
checkout을 새로 받더라도 지정한 기존 state 경로로 초기화한다. 경로를 바꾸거나 기존 state가 발견되면 먼저 담당자가 상태·백업을 확인한다.
이미 관리 자원이 들어간 상태에서 단순히 재초기화하거나 state를 옮기지 않는다.

import/apply 전후에 상태 파일을 백업하고, 담당자가 관리하는 별도 보호 위치에도 사본을 보관한다.
운영 완료에는 메인 서버 밖 노트북 등의 최종 state·실행 기록 사본과 state SHA256 일치 확인을 포함한다.
같은 디스크의 `.backup` 파일만으로는 해당 디스크 유실을 대비하지 못한다. 새 클라우드 저장소를 만들 필요는 없다.
state와 plan은 민감한 값을 포함할 수 있으므로 Git·공개 PR·CI artifact에 올리지 않는다. 폴더 권한과 로컬 디스크 보호도 확인한다.
다른 실행자로 인계할 때는 마지막 state·백업과 코드 커밋을 함께 전달하고 이전 실행은 중단한다.
잠금 파일을 임의 삭제하거나 `-lock=false`로 동시 실행하지 않는다.

CI는 AWS 인증 없이 문법·mock import 계획·계획 차단 검사를 실행한다.
실제 운영 state를 가진 지정 실행자가 정상 refresh를 수행한 계획을 검토한다.
운영 state를 복사하지 않은 CI plan을 실제 운영 변경 계획으로 쓰지 않는다. S3 상태 버킷·PR용 AWS OIDC 역할은 현재 단계의 필수 조건이 아니다.
기존 배포 역할의 권한을 넓히지 않으며, 조회 정책은 별도 지침대로 필요한 범위만 임시 적용한다.
수집기의 조회 정책이 Terraform provider의 모든 조회나 운영 변경 권한까지 보장하지는 않으므로 후속 단계에서 필요한 작업을 확인한다.

## #103 통합 범위와 후속 변경

| 단계 | 작업 | 완료 기준 |
| --- | --- | --- |
| 1단계 준비 PR | 기준 인벤토리·읽기 수집기·Terraform 골격·검증 CI·담당자 지침 | 준비 구성의 모의 테스트, fmt/init/validate/빈 plan 통과 |
| 조회·실행 준비 | 조회 권한·2차 조회, 단일 실행자와 로컬 state 경로 지정 | 필요한 네트워크 ID 확보, 조회 오류 분류, 상태 경로·백업 담당 확인 |
| #103 전체 가져오기 | 네트워크 27개·IAM 역할 3개/정책/프로파일/OIDC·프론트 S3/CloudFront·EC2/EIP/공개 키 | [통합 지침](adoption.md): 전체 조회 → 정확한 대상·변경 0 검증 → state import → 사후 변경 0 → 서버 밖 state·기록 백업/해시 확인 |
| 첫 운영 변경 | 8080의 소스를 CloudFront origin-facing 접두사 목록으로 제한 | CloudFront 경유 API/OAuth 정상, 직접 접속 차단, 롤백 방법 확인 |
| 담당자 전달 | 필요한 코드·조회 권한·검증·실행 지침을 한 묶음으로 준비 | PR마다 AWS 작업을 끼워 넣지 않고 한 작업 시간에 순차 실행 |
| 선택 개선 | SSM/22 제한, EBS 암호화, 스케줄·모니터링, 도메인/origin TLS | 항목별 별도 PR·운영 검증 |

CloudFront 접두사 목록은 SG 규칙 55개 분량을 사용하며 특정 팀 배포만 식별하지 않는다. 한도와 추가 origin 식별 방식을 검토한다.
현재 GitHub 배포는 SSH이므로 22번을 운영자 IP로만 제한하기 전에 배포 경로(SSM 등)를 먼저 확보한다.
EBS 기본 암호화는 기존 볼륨을 암호화하지 않는다. 기존 볼륨 교체는 복원 검증·실측 중단 시간·롤백을 별도 계획한다.
SQS는 CloudFormation 소유를 유지한다. 이전을 선택하면 운영 중 이중 관리를 피하는 소유권 이전 절차부터 별도 검토한다.
Terraform으로 인프라를 가져와도 DB 데이터·.env·Compose·비밀값·백업 복원이 자동 재현되는 것은 아니다.
`prevent_destroy`는 운영 백업이나 AWS 콘솔 종료 보호를 대신하지 않는다.

## 근거

- [Local backend와 상태 잠금](https://developer.hashicorp.com/terraform/language/backend/local)
- [AWS CloudFront 접두사 목록](https://aws.amazon.com/blogs/networking-and-content-delivery/limit-access-to-your-origins-using-the-aws-managed-prefix-list-for-amazon-cloudfront/)
- [Spring 전달 헤더 처리](https://docs.spring.io/spring/reference/web/webmvc/filters.html#filters-forwarded-headers)
- [ACM 내보내기 지원 인증서](https://docs.aws.amazon.com/acm/latest/userguide/acm-exportable-certificates.html): EC2 origin TLS에 ACM을 전혀 쓸 수 없다는 기존 계획의 단정은 제외한다. 비용·갱신·배포 방식은 별도 결정한다.
- [웹 콘솔 계정으로 AWS CLI 로그인](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-sign-in.html)
- [EC2 작업별 권한·자원 범위](https://docs.aws.amazon.com/service-authorization/latest/reference/list_ec2.html)
