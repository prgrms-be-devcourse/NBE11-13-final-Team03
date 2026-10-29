# 기존 인프라 전체 import: 담당자 일괄 실행

PR #103은 **기존 설정을 보존해 Terraform 관리 대상으로 등록**하는 범위다.
코드·권한·자료를 모아 전달하고 AWS 담당자는 한 번의 작업 시간에 조회→계획→등록→검증을 진행한다.
PR 병합만으로 import는 실행되지 않는다. 기존 main push는 backend 재배포를 유발할 수 있으므로 서버 가동 시간에 맞춰 병합한다.

## 관리 범위

| 범위 | 대상 |
| --- | --- |
| 네트워크 | VPC·서브넷 2·IGW·라우팅 테이블 2·인터넷 경로·서브넷 연결 2·서비스 SG 4·규칙 14, 합계 27개 |
| IAM | main/data SQS 역할·프론트 배포 역할 3개, 앱 프로파일 2개, 기존 inline 정책과 관리형 정책 연결, 연결된 계정 소유 정책, GitHub OIDC |
| 서버 | EC2 4대·root EBS 설정·IMDS·종료 보호·모니터링·프로파일 연결, main EIP/연결, team03_key 공개 키·지문 |
| 프론트 | 기존 S3 버킷·관측된 정책/공개 차단/소유권/암호화·현재 버전 관리 상태, CloudFront 배포·OAC·Function |

AWS 관리형 IAM 정책은 연결만 관리한다. 존재하지 않는 S3 선택 설정은 생성하지 않는다.
총 항목 수는 실제 정책/연결과 버킷 설정 개수에 따라 결정된다. **56개는 CI 합성 자료의 수이며 운영 예상값으로 고정하지 않는다.**
8080/22 접근, origin HTTP, IAM 신뢰/권한, 암호화 등 현재 운영 설정을 유지한다.
8080 제한·SSM 전환·EBS 암호화·역할 삭제는 후속 PR이다. SQS는 CloudFormation 소유를 유지하고 새 S3 버킷은 만들지 않는다.
스케줄·DB 데이터·.env·Compose·백업 복원·프론트 객체는 이번 관리 범위 밖이다. import만으로 전체 서비스 복구가 완성되지는 않는다.

2026-10-28 운영 담당자 확인으로 삭제된 `team03-github-actions-deploy`는 조회·import 대상에서 제외했다.
`team03-frontend-deploy`의 신뢰 정책은 실행 시 GetRole로 다시 조회해 현재 값을 그대로 사용한다.
기존 기준본은 당시 내보내기 이력이며 삭제된 역할이나 과거 신뢰 정책을 복원하는 근거로 사용하지 않는다.

## 보안 그룹 작업 순서

**현재 snapshot으로 import → 사후 변경 0 확인·서버 밖 백업 → 별도 Terraform PR로 보안 그룹 변경** 순서다.
import 전에는 8080 CloudFront 접두사 목록 제한과 `yon-db-replica` 5432의 `yon-db`·`yon-data` 소스 추가를 대기한다.
import 후에는 관리 대상 보안 그룹을 Terraform에서 변경하고 계획을 검토한다.
`yon-data` 22의 `yon-main` 참조는 03:49 UTC network snapshot에 반영돼 있다(운영 담당자 확인).
긴급 변경이 먼저 필요하면 새 snapshot과 선언을 함께 검토·커밋한 뒤 진행한다. 규칙 ID 일치 검사나 no-op 검사를 완화하지 않는다.

## 담당자가 한 번 준비할 것

1. 실행자 한 명·PC/EC2 한 곳, 리뷰어, 기존 local state 절대 경로와 별도 비공개 백업 위치를 확정한다.
2. AWS CLI 2, Python 3.9 이상, Terraform 1.17.x(검증 1.17.0)를 준비한다. provider는 lockfile의 6.70.0을 사용한다.
3. IAM 담당자가 [terraform-import-read.json](iam/terraform-import-read.json)을 실행 identity에 **인라인 정책 `YONTerraformImportReadTemporary`**로 부여한다.
   기존 서버 역할로 실행한다면 대상은 `team03-main-sqs`다. 별도 승인된 작업 identity도 사용할 수 있다.
   이 정책 하나가 기존/추가 네트워크 조회 정책을 대체한다. 기존 SQS 권한은 유지한다.
   `cat`이나 서버 파일 편집은 IAM 적용이 아니다. 콘솔 IAM 역할의 권한에 등록해야 한다.
4. 정책은 EC2 서울 리전 메타데이터, 지정 IAM 역할/프로파일/OIDC와 연결 정책, 기존 프론트 버킷 설정, CloudFront 설정/소스를 조회한다.
   생성·수정·삭제·PassRole 권한은 포함하지 않는다. 이번 import에 AdministratorAccess는 필요하지 않다.
   서버 역할에 부여하면 그 역할을 사용하는 앱도 조회 권한을 공유하므로 작업 시간에만 부여하고 완료 후 제거한다.
5. 같은 자원·상태를 동시에 수정하지 않도록 하고 다른 IaC 소유자가 있으면 먼저 확인한다.

도구가 없다면 [Terraform 1.17.0 공식 배포본](https://releases.hashicorp.com/terraform/1.17.0/)(Linux AMD64·SHA256 확인)과
[AWS CLI 2 공식 Linux 설치 안내](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)를 따라 **메인 서버 호스트에 설치**한다.
일괄 실행기는 호스트의 `aws`·`terraform` 명령을 호출하므로 `docker run amazon/aws-cli`만 있는 상태에서는 실행할 수 없다.
설치한 실행 파일이 실행 사용자의 PATH에 있는지 아래 명령으로 확인한다. 사용자 로컬 설치라면 `$HOME/.local/bin`을 PATH에 포함한다.

```bash
terraform version
aws --version
python3 --version
```

임시 정책 이름 `YONNetworkInventoryReadTemporary`, `YONNetworkImportReadTemporary`, `YONTerraformImportReadTemporary`는 Terraform 관리에서 제외한다.
같은 이름의 임시 관리형 정책 연결도 제외한다. 다른 이름을 사용했다면 실행 전에 구분한다.
임시 정책을 영구 관리 대상으로 가져오면 회수 후 재생성 계획이 생길 수 있다.

## 새 전달본 준비와 실행

최신 전달 ZIP을 기존 폴더 위에 덮어쓰지 않고 새 폴더에 푼다. 루트 README에 실제 파일명·이동 경로가 있다.
기존 network 전용 ZIP의 27개 명령은 새 전달본에서 이어 쓰지 않는다.
코드 폴더를 옮겨도 **기존 state 경로는 유지**한다. 별도 빈 state로 같은 자원을 중복 등록하지 않는다.
새 전달본의 `infra/`가 보이는 위치에서 실행한다.

계획만 확인:

```bash
python3 infra/scripts/adopt_infrastructure.py --state-dir "$HOME/yon-terraform-state"
```

승인된 실행자가 상태 등록과 사후 검증까지 일괄 진행:

```bash
python3 infra/scripts/adopt_infrastructure.py --state-dir "$HOME/yon-terraform-state" --apply
```

`--apply`도 전체를 다시 조회하고 새 계획을 검사한 뒤 **검증된 저장 계획만** 상태에 반영한다.
각 단계가 성공해야 다음 단계로 진행하며 오류를 무시하지 않는다.

1. 계정·Terraform 버전·기존 backend 경로 확인.
2. `--scope import` 전체 조회. AccessDenied를 자원 부재로 취급하지 않는다.
3. 관측값으로 비공개 tfvars·정확한 대상 목록 생성. 정책 본문·Function·헤더를 기본값으로 채우지 않는다.
4. local backend 초기화·문법 검증·기존 상태 백업.
5. 실제 계획 생성 → 정확한 주소/ID·기준 속성·모든 자원의 `no-op` 검사. 생성·수정·삭제·교체·드리프트·불완전 계획이면 중단.
6. `--apply`이면 검증된 계획으로 상태 등록 → 상태 사본 저장.
7. 다시 계획 → 모든 대상 등록 완료·pending import 없음·AWS 변경 0 확인 → `completed.json` 기록.

기존 네트워크 snapshot과 규칙 ID가 달라졌거나 EC2 추가 디스크·미지원 CloudFront 기능 등이 발견되면 검토를 위해 중단한다.
Function LIVE/DEVELOPMENT 설정·소스가 다를 때도 미배포 변경을 덮어쓰지 않도록 중단한다.
중간 실패 시 비공개 로그와 상태를 보존한다. 원인을 해결한 뒤 **같은 state 경로**로 재실행한다.
이미 등록된 주소는 기존 상태 ID까지 대조해 남은 항목만 가져온다. `destroy`·state 초기화·잠금 해제로 재시작하지 않는다.

## 결과·백업·권한 회수

화면에는 `PASS: N targets, M pending imports, 0 AWS resource changes`와 비공개 실행 폴더가 출력된다.
등록·사후 검사 완료 기준은 마지막 검사에서 pending import 0, AWS 변경 0, `completed.json` 존재다.
**`completed.json`만으로 운영 작업 전체를 완료 처리하지 않는다. 아래 서버 밖 백업까지 확인해야 한다.**

state 디렉터리 아래 `adoption-<UTC시각>/`에 원본 `inventory/`, Function 소스, `adoption.tfvars.json`, `targets.json`,
저장 계획/JSON/로그, 기존 상태의 전후 사본, 계획 해시 `review.json`과 완료 기록을 보관한다.
Terraform provider 일부 정책·코드·헤더 속성은 민감한 값으로 표시되지 않는다.
일괄 실행기는 **Terraform 출력 전체를 비공개 로그에 저장**하고 화면에는 원문을 출력하지 않는다.
Git·공개 PR·채팅·CI artifact에 이 파일들을 올리지 않는다. POSIX에서는 umask 077과 폴더 0700/파일 0600을 사용한다.
Windows ACL은 POSIX 모드로 대체되지 않으므로 운영 예시는 Ubuntu 기준이다.

담당자는 완료 직후 **메인 서버 밖의 노트북 등 접근이 제한된 저장 위치로 `~/yon-terraform-state` 전체를 복사**한다.
기존 SSH/SFTP 연결로 최종 `terraform.tfstate`와 이번 `adoption-<UTC시각>/` 실행 기록을 함께 받는다.
서버의 `sha256sum ~/yon-terraform-state/terraform.tfstate`와 사본의 SHA256을 대조하고, 사본에서 `completed.json`과 실행 기록도 확인한다.
같은 서버 디스크의 before/after 사본만으로는 외부 백업 완료로 보지 않는다. 서버를 잃어도 복구할 수 있는지 확인한다.
서비스/배포 경로를 확인한 뒤 임시 조회 정책을 제거한다. 실행자를 바꿀 때도 마지막 state와 코드 커밋을 함께 전달한다.

운영 최종 완료 조건:

- [ ] pending import 0·AWS 변경 0·`completed.json` 확인.
- [ ] 노트북 등 메인 서버 외부에 최종 state·실행 기록 복사, state 해시 일치 확인.
- [ ] 기존 서비스 접속·배포 경로 정상 확인.
- [ ] 임시 조회 정책 회수.

PR에는 코드 커밋·대상 수·pending import 0·AWS 변경 0·외부 백업/해시 대조 여부·권한 회수 여부·완료 시각을 남긴다.
state·로그·원본이나 노트북의 실제 비공개 경로를 공개 PR에 첨부하지 않는다.

## 검증 범위와 한계

- VPC 미조회 DNS 속성은 기존 값을 보존한다. 운영 조회 후 별도 변경에서 고정한다.
- EC2 launch AMI/user-data 변경을 무시해 재부팅/교체를 피한다. KeyPair 공개 키도 import 왕복 차이로 교체하지 않고 지문을 대조한다.
- root EBS는 EC2 안에서 관리하며 같은 볼륨을 별도 EBS 자원으로 이중 선언하지 않는다.
- CI의 정책·Function·주소는 합성 자료이며 운영 입력으로 사용하지 않는다.
- 실제 AWS 조회·현재 값 무변경·운영 import·서비스 접속은 담당자의 실환경 검증이 남는다.

근거: [Terraform import](https://developer.hashicorp.com/terraform/language/import),
[저장 계획 JSON](https://developer.hashicorp.com/terraform/internals/json-format),
[AWS provider 6.70.0 문서](https://github.com/hashicorp/terraform-provider-aws/tree/v6.70.0/website/docs/r),
[EC2 역할 권한 공유](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_use_switch-role-ec2.html).
