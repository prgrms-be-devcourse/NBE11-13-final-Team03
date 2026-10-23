# 독립 서버 DB 경계 — 개정안 2단계

## 범위와 공용 영향

backend와 notification-service는 독립 Gradle/Spring Boot 프로젝트를 유지한다.
같은 PostgreSQL 16 DB 안에서 다음 소유권을 사용한다.

| 프로젝트 | 로그인 계정 | 스키마·search_path |
| --- | --- | --- |
| backend | application_user | application, pg_catalog |
| notification-service | notification_user | notification, pg_catalog |

서로의 테이블·시퀀스를 직접 읽거나 쓰지 않으며 스키마 간 FK를 만들지 않는다.
기존 Application 보안과 Notification의 모든 요청 거부 설정은 그대로다.
내부 서비스 인증, 날짜 추적 통합, Outbox, 알림 업무 Entity는 이 단계에 포함하지 않는다.

두 main()은 실행 환경용 database-boundary 프로필을 항상 추가한다.
이 프로필은 역할 선택이나 Scan 분기가 아니며 프로젝트별 DB 설정만 적용한다.
프로젝트 내부 기본 Entity/Repository/Component Scan을 그대로 사용한다.
IDE main 실행, bootRun, Boot JAR 모두 같은 설정을 사용한다.

기존 테스트 슬라이스는 main()을 호출하지 않아 기존 테스트용 DB 설정을 유지한다.
새 PostgreSQL 컨텍스트 테스트는 useMainMethod=ALWAYS로 실제 실행 경로를 검증한다.

## 실행 설정

실행 전 스키마·계정 프로비저닝과 필요한 기존 데이터 이전을 완료해야 한다.
설정 파일 변경만으로 public 테이블이 이동하지 않는다.

backend/.env에는 기존 Kakao/JWT/internal/수집 설정과 함께 다음을 둔다.

```properties
DB_URL=jdbc:postgresql://localhost:5432/yon
APPLICATION_DB_PASSWORD=<application_user의 실제 비밀번호>
```

notification-service/.env에는 다음을 둔다.

```properties
DB_URL=jdbc:postgresql://localhost:5432/yon
NOTIFICATION_DB_PASSWORD=<notification_user의 실제 비밀번호>
```

계정명은 각 프로젝트 설정에 고정한다. 기존 DB_USERNAME/DB_PASSWORD는 실제 main 실행의
제한 계정 설정에 사용하지 않는다. SPRING_DATASOURCE_USERNAME 등으로 잘못 덮어쓰면
기동 검증에서 실패하도록 한다. 비밀번호의 postgres/1234 기본값은 제공하지 않는다.

dotenv는 개발용 bootRun에서만 사용한다. 운영 JAR/컨테이너에는 위 환경변수를
비밀값 관리 수단으로 주입하고 .env를 이미지에 넣지 않는다.
PORT 등 포트 설정은 1단계와 같다.

Hibernate default_schema와 Hikari connection-init-sql을 각 소유 스키마에 맞춘다.
Hikari를 거치지 않는 기존 RawIngestListener의 JDBC 연결도 올바르게 동작하도록
로그인 계정의 DB별 search_path를 관리자 SQL로 설정한다. 적용 후 기존 연결을 재사용하지 말고 재접속한다.

코드의 네이티브 SQL(업서트, ANALYZE, pg_stats 등)은 스키마 이름을 붙이지 않고 search_path에 맡긴다.
네이티브 SQL에 public. 같은 스키마 접두사를 붙이거나 search_path를 바꾸지 않는다.
pg_stats처럼 스키마를 조건으로 써야 하면 current_schema()를 사용한다.

### ddl-auto 정책

| 프로젝트 | ddl-auto | 이유 |
| --- | --- | --- |
| backend | update | 기존 스키마 변경 PR이 update에 의존해 왔고 아직 마이그레이션 도구가 없다 |
| notification-service | validate | 새 프로젝트라 처음부터 database/scripts의 수동 DDL로 관리한다 |

backend는 마이그레이션 도구(Flyway 등)를 도입하기 전까지 update를 유지한다.
application_user는 application 스키마만 소유하므로 update가 다른 스키마나 public을 바꾸지 않는다.
update는 컬럼 타입 변경·삭제를 하지 않으므로, 그런 변경은 application_user로 명시적 DDL을 적용한다.
Flyway 도입과 backend validate 전환은 후속 작업으로 팀이 함께 결정한다.

## 로컬 개발 DB

로컬 DB는 docker/compose.yaml의 postgres-db로 띄운다(전체 실행은 docker/start.sh, docker/README.md 참고).
데이터 볼륨이 비어 있을 때 다음을 파일명 순서로 자동 실행한다.

1. database/scripts/database-boundaries.sql: 두 계정과 스키마 생성
2. database/local/02-set-local-passwords.sh: 두 계정의 비밀번호 설정 (로컬 전용)
3. database/local/03-apply-notification-ddl.sh: Notification 수동 DDL을 notification_user 소유로 적용
   (Notification은 validate라 기동 전에 테이블이 있어야 한다. 새 Notification DDL 파일은 이 스크립트 목록에 추가한다.)

docker/.env에 APPLICATION_DB_PASSWORD와 NOTIFICATION_DB_PASSWORD를 둘 다 넣어야 compose가 실행된다.
bootRun으로 개발할 때 backend/.env, notification-service/.env의 비밀번호도 같은 값을 쓴다.
application 스키마의 테이블은 backend가 첫 기동에서 update로 만든다.
backend/docker-compose.yml은 이전 방식으로, docker/compose.yaml로 대체한다.

초기화 스크립트는 빈 볼륨에서만 실행된다. 기존 로컬 볼륨에 public 테이블이 있으면 둘 중 하나를 선택한다.

- 데이터를 유지할 때: 아래 관리자 SQL 1·2단계(프로비저닝, public 이전)를 로컬 DB에 직접 실행하고 비밀번호를 설정한다.
- 데이터가 필요 없을 때: `docker/`에서 `docker compose down -v`로 볼륨을 지우고 다시 올린다. 로컬 데이터가 모두 삭제된다.

운영 compose(#33 docker-compose.prod.yml)에도 같은 프로비저닝 단계가 필요하다.
두 PR 중 나중에 병합되는 쪽에서 초기화 SQL 마운트와 .env.example 항목을 맞춘다.

## 관리자 SQL: 자동 실행 금지

저장소 루트 database/scripts는 두 서버의 운영 준비 자료다.
Gradle 프로젝트나 공유 Java 모듈이 아니며 운영 JAR에 포함하지 않는다.
각 프로젝트 테스트에서만 같은 원본을 db-boundary 리소스로 복사한다.

실제 DB에 적용하기 전에 대상 DB명, 백업, 객체 목록, 타 계정 사용 여부를 확인한다.
아래 SQL은 계정/권한을 변경하므로 팀이 공유하는 DB에 무단 실행하지 않는다.
이 구현 작업에서는 실제 개발/운영 DB에 실행하지 않았다.

### 1. 프로비저닝

database/scripts/database-boundaries.sql을 DB/역할 관리자로 실행한다.

```bash
psql -h <DB호스트> -U <관리자> -d yon -v ON_ERROR_STOP=1 \
  -f database/scripts/database-boundaries.sql
```

- LOGIN 계정과 소유 스키마를 만들고 각 계정의 search_path를 설정한다.
- 상대 스키마와 public 접근, DB CREATE 권한을 차단한다.
- 기존 역할이 관리자 권한/다른 역할 멤버십을 가지거나 스키마 소유자가 다르면 중단한다.
- 기존 객체 및 새 객체 기본 권한에서 PUBLIC/상대 계정 권한을 제거한다.
- public의 PUBLIC 권한 회수는 다른 로그인에도 영향을 준다. Y 전용 DB에서만 사용한다.

비밀번호는 SQL 파일/쉘 이력에 기록하지 말고 psql의 대화형 명령으로 따로 설정한다.

```text
\password application_user
\password notification_user
```

### 2. 기존 public 데이터 보존 이전

두 서버와 쓰기 작업을 중지하고 복구 가능한 백업을 확인한 뒤
database/scripts/migrate-public-to-application.sql을 관리자로 실행한다.

```bash
psql -h <DB호스트> -U <관리자> -d yon -v ON_ERROR_STOP=1 \
  -f database/scripts/migrate-public-to-application.sql
```

현재 backend 및 구 판정 테이블의 명시적 allowlist만 이동한다.
테이블 데이터, 소유 시퀀스, 인덱스, FK, 기존 NOTIFY 트리거 연결을 보존하고 소유자를 변경한다.
notify_raw_normalization_request() 함수도 이동한다.
알 수 없는 테이블, 중복된 대상 테이블, 별도 시퀀스/함수, View/외부 테이블,
스키마 간 FK는 임의 처리하지 않고 전체 이전 트랜잭션을 롤백한다.
추가 객체/확장 모듈이 있다면 별도 검토해 명시적 이전안을 준비한다.
테이블 추가 시 allowlist도 검토한다. 3단계 날짜 추적의 opportunity_events와
event_scan_history, 4단계 outbox_events도 포함한다. 구 추적 테이블에 없는 event_id/correlation_id의
추가·백필은 이 스키마 이동 SQL이 수행하지 않는다. 구 구조 DB는 별도 이전안이 필요하다.

3단계 설치 DB의 이벤트 lease/cursor 추가와 Outbox 최초 설치는 별도
event-fanout-outbox.sql을 사용한다. 상세 순서와 검증 범위는
[이벤트 Outbox 문서](event-fanout-outbox.md)를 따른다.

실패 시 두 스키마 데이터를 수동 합치거나 public을 삭제하지 않는다.
프로비저닝과 이전은 별도 트랜잭션이므로 이전 실패 시에도 프로비저닝 권한 변경은 남는다.
기존 서버를 다시 띄우려면 이전 전 권한/설정까지 복구해야 한다.
이전 성공 후에도 기존 public 설정의 구버전 서버로 바로 롤백할 수 없다.
백업 복구나 검토된 역이전·권한 복구가 필요하다. 운영 전 복구 절차를 리허설한다.

### 3. 수집 NOTIFY 트리거와 확인

기존 트리거는 이전 시 유지된다. 최초 설치가 필요한 경우 기존
backend/src/main/resources/db/scripts/normalization-notifications.sql을
application_user와 search_path=application,pg_catalog로 접속해 별도 설치한다.
관리자로 실행한다면 SET ROLE application_user를 사용한다.
public에 다시 설치하거나 Notification에 설치하지 않는다.

데이터 건수, 시퀀스 다음 값, 인덱스/FK, 트리거/함수 소유권, 두 로그인 권한을 확인하고 서버를 실행한다.
런타임 가드는 실제 로그인·current_schema·search_path·상대 스키마 존재·상대/public 접근·관리자 권한·
역할 멤버십·DB CREATE·남은 public 테이블·스키마 간 FK를 Hibernate 초기화 전에 확인한다.
가드는 DDL 마이그레이션/전체 데이터 검증의 대체물이 아니다.

- 상대 스키마가 없으면 "notification schema exists"(backend) 또는 "application schema exists"(Notification)로 실패한다.
  두 서버는 같은 프로비저닝 SQL로 함께 준비하므로, 한쪽 스키마만 있는 DB에서는 어느 서버도 뜨지 않는다.
- public에 테이블이 하나라도 있으면 기동을 거부한다. 확장 모듈이 public에 테이블을 만드는 경우도 포함된다.
  의도된 엄격함이며, 그런 확장은 public이 아닌 별도 스키마에 설치한다.
- 두 가드 코드는 계정·스키마 이름만 다르다. 한쪽을 고치면 상대 프로젝트의 같은 파일도 함께 고친다.

PostgreSQL의 스키마별 기본 권한 회수는 전역 기본 부여를 취소하지 못하므로,
함수 PUBLIC EXECUTE를 포함한 기본 권한은 전역과 스키마 수준에서 함께 회수한다.
근거: [PostgreSQL 16 ALTER DEFAULT PRIVILEGES](https://www.postgresql.org/docs/16/sql-alterdefaultprivileges.html).

## 테스트 전략

Docker를 실행한 상태에서 각 프로젝트의 Wrapper를 사용한다.
새 테스트는 PostgreSQL 16 Testcontainers의 폐기 DB만 변경하며 실제 yon/YON_TEST_DB_URL DB에 SQL을 적용하지 않는다.
Docker가 없을 때 조용히 건너뛰도록 하지 않는다.

```bash
cd backend
./gradlew test --rerun-tasks \
  --tests 'com.team3.backend.architecture.DatabaseBoundaryPostgresTest' \
  --tests 'com.team3.backend.architecture.ApplicationDatabaseContextTest'
```

```bash
cd notification-service
./gradlew clean test bootJar
```

- SQL: 반복 실행, 양방향 CRUD/시퀀스/DDL/FK/public 차단, 레거시 데이터·제약·NOTIFY 보존,
  미검토 테이블/동명 충돌/교차 FK의 전체 이전 롤백.
- backend: 실제 main() 기동과 기존 업무 Entity/default schema, 풀 밖 JDBC 경로.
- Notification: 실제 main() 기동, 테스트 전용 Entity/Repository CRUD, 스키마 경계.
- 각 서버: 잘못된 계정·search_path·과도한 접근 권한·미이전 public 테이블의 기동 거부.
- 전체 backend 회귀 테스트와 기존 PostgreSQL 조건부 테스트는 별도로 실행한다.
  YON_TEST_DB_URL은 기존 테스트용 별도 DB에만 지정하고 운영/보존 DB를 사용하지 않는다.
- 두 Boot JAR/이미지는 1단계와 동일하게 독립 빌드한다.

Notification 업무 테이블은 아직 없으며 테스트 fixture는 운영 JAR에 포함되지 않는다.
CI에서는 각 프로젝트를 독립 검증하고 공통 관리자 SQL 변경에도 두 테스트 workflow를 실행한다.
실제 DB 권한 적용·데이터 이전·배포 성공은 테스트 결과와 별개로 확인해야 한다.
