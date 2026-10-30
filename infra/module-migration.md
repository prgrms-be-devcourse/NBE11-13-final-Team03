# 운영 import 완료 후 모듈 주소로 전환

## 범위와 전제

운영 담당자가 #111에 남긴 [완료 기록](https://github.com/prgrms-be-devcourse/NBE11-13-final-Team03/pull/111#issuecomment-6489095625)에 따르면 main `e671dfe`로 대상 52개의 flat import를 완료했고, 사후 pending imports 0·AWS 변경 0·외부 백업/해시 대조·권한 회수를 확인했다(2026-10-29 14:42:32 KST). 이 기록은 담당자 보고이며 코드 담당자의 운영 state 직접 검증은 아니다.

이후 운영자가 콘솔에서 main 8080을 CloudFront 전용으로 바꾸면서 구 규칙을 삭제하고 새 규칙을 만들었다. 이 PR은 모듈 주소 이동과 함께 그 한 건의 state 재등록을 처리한다. 운영 자원 변경은 허용하지 않는다.

네트워크, server(4회 호출), GitHub OIDC, frontend 모듈로 나눈다. 공유 KeyPair·관리형 정책 본문·동적 inline 정책과 정책 연결은 루트 주소를 유지한다. 정책 이름/ARN이 운영 조회로 정해지므로 이를 각 서버로 옮기는 데 필요한 비공개 이동 선언과 새 실행 단계를 요구하지 않기 위함이다.

`var.adoption`, network snapshot, 조회 정책, provider/CLI 버전, state 경로, 실행 명령은 기존과 같다. `prevent_destroy`와 AMI/user-data·KeyPair 무시를 유지한다. CloudFront Function의 `publish`와 Distribution의 `retain_on_delete`·`wait_for_deployment`는 `ignore_changes`로 import된 Terraform 전용 값을 유지한다. 삭제 방지는 `prevent_destroy`가 담당한다. Docker/Compose·컨테이너·DB 데이터는 관리하지 않는다.

## 적용 순서

1. 위 flat import 완료 기록과 기존 state·서버 밖 백업을 담당자가 대조한다. 이미 완료한 import를 다시 실행하지 않는다. #103 이후 인프라를 별도 변경했다면 원본과 현재 입력을 먼저 대조한다. 코드가 snapshot 차이로 중단하면 기준을 검토하며 검사를 완화하지 않는다.
2. 병합 전 이 PR 소스로 실제 운영 state의 계획만 확인한다. 담당자가 결과를 PR에 남겨 승인한 뒤 병합한다. 병합 시 기존 앱 배포가 실행될 수 있으므로 작업 시간을 맞춘다.
3. 전환 작업은 기존 실행자·한 환경에서 진행한다. **기존 `~/yon-terraform-state/terraform.tfstate`를 그대로 사용한다.** 새 빈 state로 대체하거나 중복 import하지 않는다. 전환 전 state와 실행 기록을 비공개로 백업한다.
4. 새 소스를 별도 폴더에 받고 원래와 같은 임시 조회 정책을 작업 시간에만 부여한다. 상태 전환에 AWS 쓰기·PassRole·관리자 권한은 필요 없다.
5. 아래 계획 확인 명령을 실행한다. 자동으로 현재 값을 다시 조회하고 기존 state를 백업하며 모듈을 초기화한다.

```bash
python3 infra/scripts/adopt_infrastructure.py --state-dir "$HOME/yon-terraform-state" --rebind-main-http
```

담당자/리뷰어가 비공개 저장 계획을 확인한다.

- 전체 대상·원본 ID가 일치한다.
- 구 8080 규칙이 남은 state의 최초 전환 계획은 **승인된 state 해제 1·새 규칙 import 1·주소 이동 N·AWS 변경 0**이어야 한다. 이 한 건만 이동/import 혼합의 예외다. 그 밖의 혼합은 중단한다. 이미 새 규칙으로 재등록됐다면 pending imports 0이 정상이다. 빈 state로 대체하지 않는다.
- AWS 자원 actions는 전부 `no-op`이다. 생성·수정·삭제·교체가 없어야 한다.
- 이동은 `moved.tf`에 선언한 정확한 source → destination만 허용한다. 선언 48개는 정적 범위이며, 없는 선택 S3 설정은 이동 대상도 없다. 실제 운영 대상 수나 이동 수를 CI 수치로 강제하지 않는다.
- `review.json`에 대상 수·pending imports·`address_moves`·계획 SHA256이 기록된다. 이미 전환한 state를 재실행하면 이동 0개가 정상이다.

6. 확인 후 **같은 코드·환경·state 경로**로 실행한다.

```bash
python3 infra/scripts/adopt_infrastructure.py --state-dir "$HOME/yon-terraform-state" --rebind-main-http --apply
```

새 계획을 다시 검사한 뒤 저장 계획만 적용한다. 기존 실행기의 `pending imports > 0` 조건을 `pending imports 또는 승인된 주소 이동 > 0`으로 확장해 이동만 있는 경우에도 적용한다. 수동 `terraform state mv`나 state 파일 편집은 필요 없다.

7. 사후 계획에서 **pending imports 0·주소 이동 0·AWS 변경 0**과 `completed.json`을 확인한다. 최종 state와 이번 실행 폴더를 서버 외부의 접근 제한 위치에 복사하고 state SHA256을 대조한다. 서비스 접속 확인 후 임시 조회 정책을 회수한다.

PR에 공유할 내용은 소스 커밋·전체 대상 수·전환 전/후 주소 이동 수·pending imports 0·AWS 변경 0·외부 백업·권한 회수·완료 시각이다. 원본 state·tfvars·정책·로그는 공개하지 않는다.

## 8080 규칙 한 건의 재등록

- 구 주소: `aws_vpc_security_group_ingress_rule.existing["main_http"]`, 구 ID: `sgr-05f5a7aabab29ed7d`.
- 새 주소: `module.main.aws_vpc_security_group_ingress_rule.this["main_http"]`, 새 ID: `sgr-01e8cd7a85445657c`.
- 새 소스는 CloudFront origin-facing prefix list `pl-22a6434b`, TCP 8080이다. CIDR과 prefix list·SG 참조는 택일이다.
- `network-2026-10-28.json`의 이 한 항목은 운영자의 #108 댓글에 근거해 수정했다. `source.sha256`은 원래 10월 28일 조회 원본의 해시이며 새 조회 원본의 해시가 아니다. 근거는 `reviewed_amendments`에 남겼다. 재조회 원본이 제공되면 원본 해시와 다른 규칙의 변경 여부도 대조한다.
- `--rebind-main-http`는 위 구 주소·ID·SG·기존 소스·포트와 새 snapshot을 대조한다. 다른 ID/주소나 이미 두 주소가 존재하는 상태는 거부한다.
- 계획 확인 모드는 private state 복사본에서만 `terraform state rm`을 실행한다. 실제 state는 바꾸지 않는다. 검증된 예외 import 한 건과 나머지 이동을 함께 계획한다.
- `--apply`는 먼저 복사본의 전체 계획을 검증한 뒤 원본 state가 바뀌지 않았는지 대조한다. 한 건만 실제 state에서 해제하고, 원본 state에서 새 계획을 다시 검증한 후 저장 계획을 적용한다. `before.tfstate`, `rebind.json`, `rebind-applied.json`, `review.json`을 비공개 실행 폴더에 기록한다. `state_forgets`는 예정된 해제 수이고 실제 해제 여부는 `rebind-applied.json`으로 확인한다.
- 실제 해제 후 오류가 발생하면 `before.tfstate`와 현재 state·로그를 보관하고 담당자가 복구를 검토한다. 임의 state 삭제나 자동 백업 복원, 다른 주소의 수동 해제는 하지 않는다.
- 사후에는 import 0·이동 0·AWS 변경 0이어야 한다. 새 규칙이 이미 등록된 state에 옵션을 다시 주면 해제하지 않는다.

## 운영 완료 기록

아래 값은 실제 작업 후 담당자가 채운다. 계획 확인 결과나 CI 수치를 완료 기록으로 사용하지 않는다.

| 작업 | 대상 수 | 사전 pending imports / 이동 | 사후 pending imports / 이동 / AWS 변경 | 서버 밖 백업·해시 / 권한 회수 | 완료 시각(KST) |
| --- | --- | --- | --- | --- | --- |
| #111 반영 main의 flat import (`e671dfe`, 담당자 보고) | 52 | 52 / 0 | 0 / 0 / 0 | 노트북 SHA256 일치 / 조회 정책 회수 | 2026-10-29 14:42:32 |
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
