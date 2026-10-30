# 실제 복제·라우팅 검증

Java 25와 Docker Linux 엔진에서 저장소 루트 기준:

```sh
bash backend/gradlew -p backend --no-daemon replicaTest --rerun
```

Windows PowerShell은 `backend\gradlew.bat -p backend --no-daemon replicaTest --rerun`을 사용한다.
일반 `test`와 별도 source set/task이므로 기존 테스트마다 standby를 띄우지 않는다.
기존 test resource와 EligibilityResultFixtures를 재사용하며 Rider는 이 실행 그룹에만 의존한다.

하나의 완전한 수명 시나리오에서 다음 7개 검증 구간을 수행한다.

1. PostgreSQL 16 primary의 native 역할/스키마 준비 → `pg_basebackup -R -X stream`과 replication slot으로 standby 구성. Rider는 `primaryDataSource`에만 commit. 두 서버 역할·계정과 streaming 상태 확인.
2. standby 쓰기 거부(SQLSTATE 25006), primary 데이터 불변 확인.
3. 실제 검색·상세·추천 공개 서비스의 결과와 SQL 실행 서버를 함께 확인.
4. WAL 재생을 멈추고 primary에서 저장한 값을 standby 조회가 아직 못 보는지 확인. 연결 가능한 지연을 장애 대체 정책으로 해석하지 않음.
5. 쓰기·비대상 readOnly·트랜잭션 밖 연결은 primary, WAL catch-up 후 standby 결과 갱신 확인.
6. standby 컨테이너 일시 정지·idle 연결 폐기로 새 연결 획득 실패를 유도. primary 대체와 서버가 복구된 뒤에도 적용되는 cooldown 확인.
7. 고정 sleep으로 복구를 가정하지 않고 조건 polling으로 cooldown 이후 standby 재시도 확인.

primary commit 후 `pg_current_wal_flush_lsn()`을 잡고 standby replay LSN이 해당 위치 이상일 때 일반 조회 정확도를 검증한다.
SQL에 테스트 구간 주석을 붙이는 StatementInspector와 양 서버의 PostgreSQL 로그로 실제 연결 증거를 남긴다.
DataSource/서비스를 mock으로 대체하거나 운영 트랜잭션·라우팅 코드를 바꾸지 않는다.
직접 연결의 standby 쓰기 거부는 Hikari의 readOnly 옵션과 별개로 PostgreSQL 자체 제약을 확인한다.

`backend/build/replica-evidence/`에 7개 checkpoint·서버 로그·컨테이너/네트워크 ID를 기록한다.
JUnit XML/HTML과 함께 CI에서 업로드하며 누락·실패·skip은 성공 처리하지 않는다.
Testcontainers가 컨테이너를 종료하고 JVM 종료 시 ResourceReaper가 소유 네트워크를 정리한다.
기존 개발 DB·운영 EC2·개인 `.env`를 사용하지 않으며 실제 운영 HA/승격·AWS 네트워크 검증과 구분한다.
