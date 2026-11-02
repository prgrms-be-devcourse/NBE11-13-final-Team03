# 쉼표 관심 분야 정리와 추적 매칭 전환

## 코드 변경

- #134의 선택지·검색·추천 변경을 전제로 한다. 추적의 사전 조회와 잠금 재검증도 현재 버전의
  `opportunity_categories` 안에서 대분류 또는 중분류 일치를 확인한다. 분류 행이 없는 정책은
  원문 컬럼으로 대신 매칭하지 않는다. `ELIGIBLE`의 관심 분야 비필수 정책은 유지한다.
- 관심 분야 저장 API는 기존 공백·길이·중복·30개 제한에 더해 쉼표 결합값을 400 `COMMON_001`로 거부한다.
  현재 선택지 전체와 저장값을 대조하는 검증은 추가하지 않는다. 조회 시 기존 값을 자동 분리·삭제하지 않는다.
- 기존 추적 쌍·최초 적합·소비 기록을 초기화하지 않는다. 관심 일치 결과가 달라진 쌍만 입력 키 변경으로
  다음 회차에서 재관측된다. 첫 회차의 `observed`와 신규 적합 후보가 일시적으로 증가할 수 있다.

## 적용 순서

1. data-service 분류 백필과 #134의 backend·frontend 배포를 확인한다.
   `youth-policy-categories-check.sql`에서 현재 `pending_backfill=0`인지 다시 확인한다.
2. 대상 DB·스키마와 실행 담당 한 명을 정하고 정리 대상 `user_interests` 원본을 백업한다.
   리뷰 시점의 7행은 고정된 실행 조건이 아니며 실행 직전 대상과 건수를 다시 확인한다.
3. 아래 스크립트를 기본 모드로 실행해 원본·분리값·선택지 일치 여부·사용자별 최종 개수를 확인한다.
   `교육`도 실제 현재 선택지 존재 여부로 판단한다. 옛 이름이라는 이유로 자동 치환하지 않는다.
4. 분리값이 선택지에 없거나 상한을 넘으면 자동 삭제하지 않고 사용자에게 재선택을 안내한다.
   정상 선택지에 쉼표 명칭이 있으면 재저장 차단 코드의 적용 전에도 실제 분류를 먼저 검토한다.
5. 관심 분야 변경 요청이 없는 점검 시간에 명시적 적용 모드로 실행한다. 기본 모드도 잠깐 테이블 쓰기 잠금을 잡는다.
   예외가 있으면 전체 트랜잭션이 롤백된다. 정상일 때만 개별 값 삽입과 원본 행 삭제를 함께 커밋한다.
   중복은 건너뛰고 생성 시각은 보존한다. 재실행 시 정리할 행이 없으면 데이터 변경이 없다.
6. 이번 backend를 배포하고 사용자별 관심 분야·추천·추적 관측을 확인한다. 자동 발행·푸시 플래그는 임의로 켜지 않는다.

```bash
# 대상 환경에서 application_user 권한으로 연결한다. 비밀번호는 프롬프트/기존 보안 설정을 사용한다.
psql -h <DB_HOST> -U application_user -d <DB_NAME> -W \
  -f database/scripts/user-interest-comma-cleanup.sql

# 미리보기 확인·백업 완료 후 명시적으로 적용
psql -h <DB_HOST> -U application_user -d <DB_NAME> -W \
  -v apply=true -f database/scripts/user-interest-comma-cleanup.sql
```

이 스크립트는 앱 기동 시 자동 실행하지 않는다. 코드 테스트는 격리된 PostgreSQL에서 실행하며,
운영 SQL 실행·실제 원본 백업·실제 사용자 확인을 대신하지 않는다.

## 검증

2026-11-02 관심 분야·Match·아키텍처 관련 20개 클래스, 168개 테스트를 실행했으며
실패·오류·건너뜀은 0이다. PostgreSQL은 Testcontainers의 별도 컨테이너를 사용했다.
정리 SQL 6개 사례에는 기본 롤백, 분리·중복 제거·재실행, 빈 값·없는 선택지·30개 초과·정상 쉼표 선택지 차단이 포함된다.

```bash
cd backend
./gradlew test \
  --tests 'com.team3.backend.interest.*' \
  --tests 'com.team3.backend.tracking.service.Match*' \
  --tests 'com.team3.backend.architecture.*' bootJar
```

전체 backend 회귀·운영 선택지의 실제 쉼표 명칭 조회·운영 SQL 적용은 별도 확인 항목이다.
