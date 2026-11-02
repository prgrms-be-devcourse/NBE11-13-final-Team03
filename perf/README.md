# 성능·부하 측정 (k6)

EC2 t3.small 4대(메인·데이터·DB·복제본)를 Docker로 모사하고, 사용자 API 혼합 흐름과 매일 수집이 겹치는 상황을 k6로 반복 측정한다.
계획: Claude Docs "Y:ON 성능·부하 테스트 작업 계획".

> 결과는 **같은 모사 환경 안에서 개선 전·후를 비교**하는 데만 쓴다. 호스트 CPU 세대, EBS I/O, 서버 간 네트워크, CloudFront 구간은 재현하지 않으므로 실제 EC2 처리량으로 옮겨 말하지 않는다.

## 준비물

- Docker Desktop (WSL2). `%UserProfile%\.wslconfig`에 아래처럼 두고 `wsl --shutdown` 후 Docker Desktop을 다시 켠다.

  ```ini
  [wsl2]
  processors=12
  memory=12GB
  ```

- Git Bash (Git for Windows에 포함). Java·k6·Python 설치는 필요 없다. 빌드는 JDK 25 컨테이너, k6·집계는 컨테이너로 돈다.
- 측정 스택의 비밀값(DB 비밀번호·내부 키·JWT 키)은 `init`이 `perf/secrets.env`에 임의로 만든다. 로컬 개발용 `docker/.env`는 쓰지 않는다.
- 측정 중 PC 절전 모드를 끈다.

## 개선 전 측정 (한 번에)

저장소 루트에서 Git Bash로:

```bash
git switch main && git pull --ff-only
bash perf/measure-before.sh --smoke-only   # 점검 → 빌드 → 시드 → 스모크까지 (약 1~1.5시간). 먼저 이것으로 확인 권장
bash perf/measure-before.sh                # 이어서 반복 측정 → 결과 문서 (스모크 이후 약 10시간, 끝난 단계는 건너뜀)
```

끝나면 `perf/results/before/RESULT.md`가 생긴다. 중간에 멈추면 같은 명령을 다시 실행한다(끝난 회차는 건너뜀).
회차를 줄이려면 `--runs 5`.

## 개선 후 비교

```bash
git switch <개선 브랜치> && bash perf/run.sh build opt1
bash perf/run.sh bench --label compare --versions base,opt1 --scenarios perf,loadA,loadB,probe
```

회차마다 base→opt1 / opt1→base 순서를 바꿔 번갈아 실행한다. `perf/results/compare/report.md`에 변화율·p 값·판정이 나온다.

## 단계별 명령 (`perf/run.sh`)

| 명령 | 내용 |
| --- | --- |
| `init` | 환경 점검(Docker·코어·메모리), `perf.env`·`secrets.env` 생성 |
| `build <tag>` | 지금 체크아웃된 코드로 이미지 3개를 `<tag>`로 빌드 |
| `seed` | 측정용 DB 스냅샷 생성 (수집 14만 건·정규화·사용자 1,000명, 20~40분) |
| `smoke` | 시나리오 4종을 짧게 한 번씩 (`results/smoke`) |
| `bench --label L --versions a,b --runs N` | 반복 측정 |
| `report <label>` | 집계표 다시 만들기 |
| `down` | 측정 스택 내리기 (스냅샷 유지) |

## 시나리오

| 키 | 파일 | 내용 | 기본 길이 |
| --- | --- | --- | --- |
| `perf` | `k6/scenarios/perf-baseline.js` | API 12개를 하나씩 5 요청/초로 2분씩 (단독 응답 시간) | 약 26분 |
| `probe` | `k6/scenarios/load-probe.js` | 혼합 흐름을 동시 접속 100→600명 상당으로 2분씩 올림 (한계 탐색) | 약 13분 |
| `loadA` | `k6/scenarios/load-mixed.js` | 동시 접속 100명 상당 혼합 흐름, 측정 구간 10분 | 약 16분 |
| `loadB` | `k6/scenarios/load-with-collection.js` | loadA + 측정 3분 뒤 두 출처 수집 접수(전날 대비 10% 변경) | 약 21분 |

회차마다 DB를 스냅샷으로 되돌리고 서버를 새로 띄운다. 워밍업·증가·감소 구간은 집계에서 뺀다.

혼합 흐름(방문 1회 = 반복 1회, 화면 사이 1~3초 대기): 비회원 탐색 40%, 로그인 탐색 45%, 저장 10%, 알림 5%.
동시 접속 100명은 방문 길이(약 7.5초) 기준 **방문 약 13회/초, 요청 약 66회/초**로 환산한다. 실제 방문 길이는 결과의 `visits_per_s`, `vus_max`로 확인한다.

기준값(11/01 확정): 조회 p95 < 500ms, 추천·판정 목록 p95 < 1,000ms, 실패율 < 1%.

## 환경 구성 (`compose.perf.yaml`)

| 모사 서버 | 컨테이너 | CPU | 메모리 | 힙 |
| --- | --- | --- | --- | --- |
| main | backend + notification-service | `CPUSET_MAIN` 코어 2개 공유 | 1.4GiB + 0.6GiB (각 swap 같은 양) | 960m / 480m |
| data | data-service | `CPUSET_DATA` 코어 2개 | 2GiB + swap 2GiB | 1400m |
| db | PostgreSQL 16 | `CPUSET_DB` 코어 2개 | 2GiB + swap 2GiB | — |
| db-replica (선택) | 스트리밍 복제본 | `CPUSET_REPLICA` | 2GiB | — |
| 측정용 | ext-mock, k6, tools | `CPUSET_TOOLS` | 제한 없음 | — |

- 외부 API(고용24·온통청년·법정동코드)는 `mock/ext_mock.py`가 합성 데이터로 대신한다. 응답 지연은 과거 고용24 실측 표본(p50 139ms, p95 273ms).
- 09:30 정기 수집은 끈다(`COLLECTION_SCHEDULE_ENABLED=false`). 수집은 시드와 loadB에서만 관리자 API로 접수한다.
- 인증은 측정 전용 키로 미리 발급한 Access Token을 `access_token` 쿠키로 보낸다. CSRF는 같은 임의 값을 `XSRF-TOKEN` 쿠키와 `X-XSRF-TOKEN` 헤더로 보낸다.
- DB에는 측정용으로 `pg_stat_statements`, `auto_explain`(200ms 초과 계획을 로그로)만 더했다. 개선 전·후 동일.
- 운영에 적용된 수동 SQL(정규화 NOTIFY 트리거, 검색 제목 생성 컬럼, 검색 인덱스)은 시드 때 같은 순서로 적용한다. 파티셔닝 등 다른 수동 SQL을 운영에 적용했다면 `PERF_EXTRA_SQL`에 넣는다.
- 운영 메인 서버가 `DB_REPLICA_URL`을 쓰면 `PERF_REPLICA=1`.
- t3 CPU 크레딧이 바닥난 상태 참고 측정: `./perf/run.sh bench --label credit --profile credit --runs 1` (반복 비교에는 쓰지 않는다).

## 회차별 기록 (`results/<label>/<시나리오>/<버전>/run-NN/`)

| 파일 | 내용 |
| --- | --- |
| `summary.json` | 구간별 API p50·p95·p99·실패율, 처리량, dropped_iterations, 컨테이너 CPU·메모리, DB 활성 연결·대기 |
| `k6.csv.gz`, `k6-summary.json`, `k6.log` | k6 원자료 |
| `docker_stats.csv` | 컨테이너 CPU%(코어 1개=100%), 메모리, 1초 간격 |
| `pg_activity.csv` | 활성 연결, 락 대기, 정규화 대기 요청 수, 가장 많은 대기 이벤트, 2초 간격 |
| `pg_stat_statements.tsv` | 총 실행 시간 상위 60개 쿼리 |
| `pg_tables.csv` | 테이블별 순차 스캔·인덱스 스캔·튜플 변경 |
| `jfr/*.jfr` | JVM Flight Recorder (JDK Mission Control로 열기: Hot Methods, Lock, GC, Socket) |
| `cpu_stat_*.txt` | cgroup CPU 스로틀 (credit 프로필에서 의미 있음) |
| `*.log` | 서버 로그 (`db.log`에 auto_explain 계획) |

## 병목을 찾는 순서

1. `report.md`에서 p95가 큰 API와 `p95 × 호출 비중`이 큰 API를 고른다.
2. 같은 회차 `summary.json`의 `resources`에서 CPU가 200%(코어 2개)에 붙은 서버가 있는지 본다. CPU가 남는데 느리면 대기(커넥션 풀·락·외부 호출)를 의심한다.
3. `pg_stat_statements.tsv` 상위 쿼리를 `EXPLAIN (ANALYZE, BUFFERS)`로 확인하고, `db.log`의 auto_explain 계획을 본다.
4. JFR에서 해당 API의 Hot Methods·Lock·JDBC 대기를 본다.
