# 네트워크 import는 전체 인프라 일괄 실행으로 통합

PR #103의 범위가 기존 인프라 전체 import로 확장됐다.
**실행은 [전체 인프라 import 지침](adoption.md)을 사용한다.** 기존 network 전용 ZIP의 27개 명령은 새 전달본에서 이어 실행하지 않는다.

네트워크 대상 27개와 관측값은 유지하고 주소만 network/서버 모듈 구조로 옮긴다. 기존 flat 주소에서 import한 state는 `moved.tf`로 연결한다. 새 실행기도 **같은 local state 경로**를 사용하며 원본 ID와 승인된 이동만 대조한다. 빈 state를 새로 만들지 않는다. [운영 state 모듈 전환 지침](module-migration.md)을 확인한다.

| 대상 | 개수 |
| --- | ---: |
| VPC·서브넷·IGW | 4 |
| 라우팅 테이블·인터넷 경로·서브넷 연결 | 5 |
| 서비스 SG·인바운드·아웃바운드 규칙 | 18 |
| 합계 | 27 |

main 라우팅 테이블은 기존 rtb ID로 가져오고 VPC main 연결을 교체하지 않는다.
현재 8080/22 접근 정책과 미조회 VPC DNS 값을 보존한다. default SG·NACL·DHCP 옵션은 관리 대상에서 제외한다.
기준값과 원본 SHA256은 [network-2026-10-28.json](inventory/network-2026-10-28.json)에 남아 있다.
