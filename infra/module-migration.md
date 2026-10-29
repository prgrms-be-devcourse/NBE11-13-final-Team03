# 운영 import 완료 후 모듈 주소로 전환

## 범위와 전제

운영 import 완료 후 진행한다. 2026-10-29 운영 담당자 확인 시점에는 #111의 보정 코드로 계획만 확인했으며, 결과는 대상 52개·pending imports 52·AWS 변경 0이었다. 이는 import 완료 기록이 아니다. 이번 변경은 flat 주소로 import를 완료한 기존 AWS 자원을 보존하고 Terraform 주소만 옮긴다. 코드 담당자는 운영 state나 비공개 입력을 받거나 운영 apply를 실행하지 않았다.

네트워크, server(4회 호출), GitHub OIDC, frontend 모듈로 나눈다. 공유 KeyPair·관리형 정책 본문·동적 inline 정책과 정책 연결은 루트 주소를 유지한다. 정책 이름/ARN이 운영 조회로 정해지므로 이를 각 서버로 옮기는 데 필요한 비공개 이동 선언과 새 실행 단계를 요구하지 않기 위함이다.

`var.adoption`, network snapshot, 조회 정책, provider/CLI 버전, state 경로, 실행 명령은 기존과 같다. `prevent_destroy`와 AMI/user-data·KeyPair 무시를 유지한다. CloudFront Function의 `publish`와 Distribution의 `retain_on_delete`·`wait_for_deployment`는 `ignore_changes`로 import된 Terraform 전용 값을 유지한다. 삭제 방지는 `prevent_destroy`가 담당한다. Docker/Compose·컨테이너·DB 데이터는 관리하지 않는다.

## 적용 순서

1. #111이 반영된 main의 **flat 주소 구성**으로 운영 import(`--apply`)를 실행한다. 사후 pending imports 0·AWS 변경 0·`completed.json`을 확인하고 서버 밖 state 백업/해시 대조·임시 조회 권한 회수를 완료한다. 완료 기록이 없으면 이 모듈 전환을 진행하지 않는다. #103 이후 인프라를 별도 변경했다면 원본과 현재 입력을 먼저 대조한다. 코드가 snapshot 차이로 중단하면 기준을 검토하며 검사를 완화하지 않는다.
2. flat import 완료 후, #111의 보정을 포함한 main 기준의 이 모듈화 PR을 검토·병합한다. 병합 시 기존 앱 배포가 실행될 수 있으므로 작업 시간을 맞춘다.
3. 전환 작업은 기존 실행자·한 환경에서 진행한다. **기존 `~/yon-terraform-state/terraform.tfstate`를 그대로 사용한다.** 새 빈 state로 대체하거나 중복 import하지 않는다. 전환 전 state와 실행 기록을 비공개로 백업한다.
4. 새 소스를 별도 폴더에 받고 원래와 같은 임시 조회 정책을 작업 시간에만 부여한다. 상태 전환에 AWS 쓰기·PassRole·관리자 권한은 필요 없다.
5. 아래 계획 확인 명령을 실행한다. 자동으로 현재 값을 다시 조회하고 기존 state를 백업하며 모듈을 초기화한다.

```bash
python3 infra/scripts/adopt_infrastructure.py --state-dir "$HOME/yon-terraform-state"
```

담당자/리뷰어가 비공개 저장 계획을 확인한다.

- 전체 대상·원본 ID가 일치한다.
- 최초 전환에서는 pending imports **0·승인된 주소 이동 N(1개 이상)·AWS 변경 0**이어야 한다. 빈 state에서 모듈 주소로 바로 import하는 경로는 이번 운영 전환 순서에서 사용하지 않는다.
- pending imports는 **0**이다. 이동과 신규 import가 함께 있으면 실행기가 중단한다.
- AWS 자원 actions는 전부 `no-op`이다. 생성·수정·삭제·교체가 없어야 한다.
- 이동은 `moved.tf`에 선언한 정확한 source → destination만 허용한다. 선언 48개는 정적 범위이며, 없는 선택 S3 설정은 이동 대상도 없다. 실제 운영 대상 수나 이동 수를 CI 수치로 강제하지 않는다.
- `review.json`에 대상 수·pending imports·`address_moves`·계획 SHA256이 기록된다. 이미 전환한 state를 재실행하면 이동 0개가 정상이다.

6. 확인 후 **같은 코드·환경·state 경로**로 실행한다.

```bash
python3 infra/scripts/adopt_infrastructure.py --state-dir "$HOME/yon-terraform-state" --apply
```

새 계획을 다시 검사한 뒤 저장 계획만 적용한다. 기존 실행기의 `pending imports > 0` 조건을 `pending imports 또는 승인된 주소 이동 > 0`으로 확장해 이동만 있는 경우에도 적용한다. 수동 `terraform state mv`나 state 파일 편집은 필요 없다.

7. 사후 계획에서 **pending imports 0·주소 이동 0·AWS 변경 0**과 `completed.json`을 확인한다. 최종 state와 이번 실행 폴더를 서버 외부의 접근 제한 위치에 복사하고 state SHA256을 대조한다. 서비스 접속 확인 후 임시 조회 정책을 회수한다.

PR에 공유할 내용은 소스 커밋·전체 대상 수·전환 전/후 주소 이동 수·pending imports 0·AWS 변경 0·외부 백업·권한 회수·완료 시각이다. 원본 state·tfvars·정책·로그는 공개하지 않는다.

## 운영 완료 기록

아래 값은 실제 작업 후 담당자가 채운다. 계획 확인 결과나 CI 수치를 완료 기록으로 사용하지 않는다.

| 작업 | 대상 수 | 사전 pending imports / 이동 | 사후 pending imports / 이동 / AWS 변경 | 서버 밖 백업·해시 / 권한 회수 | 완료 시각(KST) |
| --- | --- | --- | --- | --- | --- |
| #111 반영 main의 flat import | 미확인 | 미확인 | 미확인 | 미확인 | 미확인 |
| 모듈 주소 전환 | 미확인 | 0 / N (실행 시 확인) | 미확인 | 미확인 | 미확인 |

## 실패와 복구

예상하지 않은 이전 주소, ID 차이, 신규 import 혼합, 변경/삭제, 드리프트가 있으면 실행기가 중단한다. 상태와 비공개 로그를 보관하고 원인을 해결한 뒤 같은 경로로 재실행한다. 부분 적용 상태에서도 승인된 주소만 연결하며 이미 옮긴 항목은 현재 주소의 ID를 대조한다.

적용 전에는 예전 소스를 사용할 수 있다. **한 번 모듈 주소로 적용한 state에는 예전 flat 소스를 그대로 실행하지 않는다.** 장애 시 상태 담당자가 정확한 전후 state와 코드 쌍을 검토한다. 새 state 생성·state 삭제·destroy로 복구하지 않는다.

`moved.tf`는 적용 직후 삭제하지 않는다. 다른 실행 환경/보관 state가 과거 주소를 사용할 수 있어 이동 이력을 유지한다. 후속 설정 코드화나 보안 그룹 변경은 별도 PR로 진행한다.

## 오프라인 검증

```bash
python3 -m unittest discover -s infra/tests -v
terraform -chdir=infra/terraform fmt -check -recursive
terraform -chdir=infra/terraform init -input=false -lockfile=readonly
terraform -chdir=infra/terraform validate
terraform -chdir=infra/terraform test -json -verbose > /tmp/yon-terraform-tests.jsonl
python3 infra/tests/verify_module_migration.py /tmp/yon-terraform-tests.jsonl
```

마지막 검사는 mock 자료로만 flat state를 만들고 네이티브 Terraform 계획·저장 계획 적용·사후 계획을 검증한다. 모의 공급자가 생략하는 기본값은 합성 state에 보완하며 운영 선언을 수정하지 않는다. 임시 복사본에서 `-refresh=false`와 모든 관련 서비스의 loopback endpoint를 사용해 AWS 호출을 차단한다. 로컬/CI 합격은 실제 운영 최신 state의 무변경을 대신하지 않는다.

근거: [Terraform 모듈 리팩터링](https://developer.hashicorp.com/terraform/language/modules/develop/refactoring), [moved 선언](https://developer.hashicorp.com/terraform/language/block/moved).
