# 2단계: 기존 네트워크를 Terraform 상태에 등록

이번 작업은 운영 자원을 새로 만들거나 접근 정책을 바꾸지 않고 기존 네트워크를 상태 파일에 등록한다.
PR 병합만으로 import는 실행되지 않는다. 실제 AWS 계획·import·후속 무변경 확인은 지정 실행자의 작업이다.
`main` 병합은 기존 워크플로에 따라 backend 재배포를 유발할 수 있으므로 서버 가동 시간과 맞춘다.

## 대상과 현재 상태

계정 `174170816230`, 서울 `ap-northeast-2`. 기준은 2026-10-28 03:48 UTC에 시작한 network 조회(`complete=true`)다.
[정리한 인벤토리](inventory/network-2026-10-28.json)에 원본 파일명·SHA256과 import ID를 보관했다.

| 대상 | 개수 | Terraform 주소 |
| --- | ---: | --- |
| VPC | 1 | `aws_vpc.main` |
| 퍼블릭 서브넷 | 2 | `aws_subnet.public["public_a" / "public_b"]` |
| 인터넷 게이트웨이 | 1 | `aws_internet_gateway.main` |
| main·public 라우팅 테이블 | 2 | `aws_route_table.main` / `.public` |
| 인터넷 기본 경로 | 1 | `aws_route.internet` |
| 서브넷-라우팅 테이블 연결 | 2 | `aws_route_table_association.public[...]` |
| 서비스 보안 그룹 | 4 | `aws_security_group.service[...]` |
| 인바운드 규칙 | 10 | `aws_vpc_security_group_ingress_rule.existing[...]` |
| 아웃바운드 규칙 | 4 | `aws_vpc_security_group_egress_rule.existing[...]` |
| 합계 | **27** | 전 항목 `prevent_destroy = true` |

main 라우팅 테이블은 일반 `aws_route_table`로 기존 `rtb-*` ID를 import한다. 기존 VPC main 연결을 교체하지 않는다.
해당 연결은 별도 관리 자원으로 선언하지 않으며 VPC에서 읽은 main 테이블 ID를 계획 검사기가 기준 ID와 대조한다.
로컬 경로는 AWS가 관리하며, public 테이블의 인터넷 경로만 별도 `aws_route`로 선언한다.
SG 내부 ingress/egress 블록과 독립 규칙을 혼용하지 않는다. 현재 main 8080/22의 `0.0.0.0/0`은 그대로 유지한다.

VPC DNS 속성 3개는 제공된 `DescribeVpcs` 결과에 없다. `enable_dns_support`만 기존 값 보존을 위해 `ignore_changes`하고,
hostnames·network address usage metrics는 optional/computed 값을 명시하지 않는다. 실제 provider 조회값을 확인한 뒤 별도 변경에서 코드에 고정한다.
모의 테스트의 DNS 값은 테스트용이며 운영 값의 증거가 아니다.
default SG·NACL·DHCP 옵션, EC2·EIP·IAM·CloudFront·SQS는 이번 관리 대상에 포함하지 않는다.

## 담당과 사전 준비

1. 코드 담당자: 구성·CI·실행 지침 제공. 실제 AWS 계획이 확인되기 전 PR을 Draft로 유지한다.
2. AWS 담당자: 실행할 PC/EC2 **한 곳**, 단일 실행자, 리뷰어, 상태 경로와 비공개 백업 위치를 확정한다.
3. AWS 담당자: AWS CLI 2, Python 3.9 이상, Terraform **1.17.0**과 lockfile의 AWS provider **6.70.0**을 준비한다.
   Terraform은 [공식 설치 경로](https://developer.hashicorp.com/terraform/install)에서 설치하고 `terraform version`으로 확인한다.
4. IAM 권한 담당자: [기존 조회 정책](iam/network-inventory-read.json)과 [provider 추가 조회 정책](iam/network-import-read.json)을 실행 역할에 임시 적용한다.
   기존 임시 정책을 제거했다면 두 정책을 모두 검토한다. 콘솔 → IAM → 역할 → 실행 역할(예: `team03-main-sqs`) → 권한 → 인라인 정책 추가 → JSON.
   추가 정책 이름 예시는 `YONNetworkImportReadTemporary`다. 추가 작업은 `DescribeNetworkAcls`, `DescribeTags`, 해당 VPC의 `DescribeVpcAttribute`뿐이다.
   AWS 생성·수정·삭제 권한을 추가할 필요가 없다. 정책 변경은 코드/PR에서 자동 실행하지 않는다.
5. 실행자와 리뷰어는 인벤토리 이후 콘솔 변경이 있었는지 확인한다. 다른 도구가 같은 자원을 관리하고 있다면 중단한다.

CloudShell과 새 S3 버킷은 필요 없다. 기존 프론트 버킷을 상태 보관용으로 공유하지 않는다.
state/plan은 민감한 자료이므로 사용자 전용 경로에 보관하고 Git·채팅·공개 PR·CI artifact에 올리지 않는다.
SSH 키나 장기 AWS Access Key를 이 문서에 넣지 않는다. 지정 환경의 기존 승인된 인증을 사용한다.

## 1. 코드·실행 환경과 상태 경로 확인

아래 명령은 Ubuntu 기준이며 저장소/전달 ZIP의 `infra/`가 보이는 루트에서 실행한다.
PR의 정확한 커밋을 사용하고 실행 기록에 코드 커밋·실행자·시각을 남긴다.
실행 환경을 바꿔도 기존 상태 파일을 이어 받아야 한다. 새 빈 state로 별도 실행하지 않는다.

```bash
set -euo pipefail
umask 077
aws sts get-caller-identity --query '{Account:Account,Arn:Arn}' --output json --no-cli-pager
test "$(aws sts get-caller-identity --query Account --output text --no-cli-pager)" = '174170816230'
terraform version
export TF_STATE_DIR="$HOME/yon-terraform-state"
mkdir -p "$TF_STATE_DIR"
chmod 700 "$TF_STATE_DIR"
```

`TF_STATE_DIR`는 담당자가 승인한 **기존 절대 경로**로 맞춘다. 다른 경로에 state가 있는지 먼저 확인한다.
기존 관리 자원이 있거나 경로가 다르다면 초기화/이전을 진행하지 말고 담당자가 state 내용을 확인한다.
백업 없이 state를 덮어쓰거나 `.terraform/`을 지워 경로 확인을 우회하지 않는다.

```bash
set -euo pipefail
terraform -chdir=infra/terraform init -input=false -lockfile=readonly \
  -backend-config="path=$TF_STATE_DIR/terraform.tfstate"
terraform -chdir=infra/terraform validate
```

backend 변경·마이그레이션 요구가 나오면 자동 동의하지 않는다. 초기 import는 이 관리 범위가 아직 등록되지 않은 상태에서 실행한다.
기존 state에 27개가 모두 등록되어 있다면 재import하지 않고 아래 사후 검사부터 실행한다.
일부만 등록되어 있으면 첫 import 검사가 실패하도록 되어 있으므로 중단하고 실행 기록·state를 확인한다.

## 2. 실제 AWS 계획 저장과 리뷰

정상 refresh를 사용한다. `-refresh=false`, `-target`, `-lock=false`로 검사를 우회하지 않는다.
조회 권한 오류가 나면 멈추고 오류의 작업 이름을 IAM 담당자에게 전달한다. 생성 권한을 부여해 해결하지 않는다.

```bash
set -euo pipefail
export TF_RUN_DIR="$TF_STATE_DIR/network-import-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir "$TF_RUN_DIR"
if test -f "$TF_STATE_DIR/terraform.tfstate"; then
  cp -p "$TF_STATE_DIR/terraform.tfstate" "$TF_RUN_DIR/before.tfstate"
fi
terraform -chdir=infra/terraform plan -input=false -lock-timeout=60s \
  -out="$TF_RUN_DIR/import.tfplan"
terraform -chdir=infra/terraform show -json "$TF_RUN_DIR/import.tfplan" > "$TF_RUN_DIR/import-plan.json"
python3 infra/scripts/check_import_plan.py "$TF_RUN_DIR/import-plan.json" --phase import
```

필수 결과: **27 to import, 0 to add, 0 to change, 0 to destroy**, 검사기 `PASS`.
검사기는 정확한 27개 주소·import ID·기준 속성을 비교하고 생성/수정/삭제/교체·누락·추가·드리프트·불완전 계획을 거부한다.
예를 들어 서브넷 연결 import ID는 `subnet-ID/rtb-ID`, 경로는 `rtb-ID_0.0.0.0/0` 형식이다.
계획 파일 전체를 공개하지 말고 리뷰어가 같은 비공개 환경에서 확인한다.

**검사가 실패하거나 계획에 예상 외 변경이 있으면 다음 단계로 가지 않는다.** 실제 조회값을 기준으로 코드/인벤토리의 차이를 검토한 후 재계획한다.
이 단계의 허용 결과는 AWS 네트워크를 바꾸지 않는 상태 등록뿐이다. `prevent_destroy`만으로 수정이 차단되지는 않는다.

## 3. 검토한 계획만 상태에 반영

실행자와 리뷰어가 2번의 무변경 결과를 확인한 후 같은 커밋·같은 상태 경로·같은 저장 계획을 사용한다.
운영 콘솔을 동시에 수정하지 않는다. 계획이 오래됐거나 AWS 구성이 바뀌었다면 2번부터 다시 실행한다.
다음은 AWS 변경 대신 로컬 상태에 기존 자원을 등록한다. 별도 일반 `apply`나 `destroy`를 실행하지 않는다.

```bash
set -euo pipefail
python3 infra/scripts/check_import_plan.py "$TF_RUN_DIR/import-plan.json" --phase import
terraform -chdir=infra/terraform apply -input=false -lock-timeout=60s "$TF_RUN_DIR/import.tfplan"
cp -p "$TF_STATE_DIR/terraform.tfstate" "$TF_RUN_DIR/after.tfstate"
```

저장 계획을 바꾸거나 재생성했다면 JSON도 다시 만들고 검사를 재실행한다.
중간 실패로 일부 state만 기록된 경우 무작정 초기화/재실행하지 않는다. 기록된 주소와 마지막 백업부터 확인한다.

## 4. import 후 무변경 확인과 종료

```bash
set -euo pipefail
terraform -chdir=infra/terraform plan -input=false -lock-timeout=60s \
  -out="$TF_RUN_DIR/after.tfplan"
terraform -chdir=infra/terraform show -json "$TF_RUN_DIR/after.tfplan" > "$TF_RUN_DIR/after-plan.json"
python3 infra/scripts/check_import_plan.py "$TF_RUN_DIR/after-plan.json" --phase after
terraform -chdir=infra/terraform state list
```

필수 결과: **No changes**, 검사기 `PASS`(27개, pending import 없음).
상태·실행 기록은 담당자가 지정한 별도 보호 위치에도 백업한다. 같은 디스크의 자동 `.backup`만으로 서버 유실에 대비하지 않는다.
공개 PR에는 커밋·Terraform/provider 버전·27개 등록 여부·변경 수 0·완료 시각만 남기고 state/plan은 첨부하지 않는다.
AWS 담당자는 기존 서비스 접속과 배포 경로도 확인하고 임시 조회 정책 두 개를 제거한다. 기존 SQS 권한은 보존한다.

초기 무변경 계획 확인 후 PR을 Ready로 바꾸고 리뷰/병합할 수 있다. 운영 완료는 이 사후 검사까지 통과한 시점이다.
8080 CloudFront 제한과 IAM·프론트·EC2 import는 다음 별도 단계에서 진행한다.
등록 취소가 필요해도 `destroy`로 되돌리지 않는다. AWS가 바뀌지 않았는지 확인한 뒤 지정 실행자가 백업과 state 기록을 대조해 복구 방법을 결정한다.

## 검증의 범위와 근거

개발 환경/CI: Python 테스트, Terraform fmt/init/validate, mock provider import 계획.
모의 응답은 구성·import 문법 검증용이며 실제 AWS 권한·현재 속성·무변경·상태 반영 성공의 증거를 대신하지 않는다.
실제 import 전에 이 문서의 2번 결과가 필요하며 운영 완료에는 4번 결과가 필요하다.

- [Terraform import 블록](https://developer.hashicorp.com/terraform/language/import)
- [Terraform 저장 계획 JSON 형식](https://developer.hashicorp.com/terraform/internals/json-format)
- [AWS provider 6.70.0 route table import](https://github.com/hashicorp/terraform-provider-aws/blob/v6.70.0/website/docs/r/route_table.html.markdown)
- [AWS provider 6.70.0 route table association import](https://github.com/hashicorp/terraform-provider-aws/blob/v6.70.0/website/docs/r/route_table_association.html.markdown)
- [AWS provider 6.70.0 VPC 속성 구현](https://github.com/hashicorp/terraform-provider-aws/blob/v6.70.0/internal/service/ec2/vpc_.go)
