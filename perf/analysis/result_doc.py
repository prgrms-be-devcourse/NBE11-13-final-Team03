"""측정 묶음 하나의 결과 문서(RESULT.md)를 만든다: 조건 → 기준값 판정 → 상세 집계표 → 느린 쿼리 → 파일 안내.

  python analysis/result_doc.py results/before
"""
import glob
import json
import os
import statistics
import sys
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import report  # noqa: E402


def load(path, default=None):
    try:
        return json.load(open(path, encoding="utf-8"))
    except (OSError, ValueError):
        return default


def ts(t):
    return datetime.fromtimestamp(t, KST).strftime("%Y-%m-%d %H:%M") if t else "-"


def med_range(vals):
    vals = [v for v in vals if v is not None]
    if not vals:
        return "-", None
    m = statistics.median(vals)
    return f"{m:,.1f} ({min(vals):,.1f}~{max(vals):,.1f}, n={len(vals)})", m


def main(label_dir):
    report.main(label_dir)  # report.md, report.csv 최신화
    plan = load(os.path.join(label_dir, "plan.json"), {})
    host = load(os.path.join(label_dir, "host.json"), {})
    runs = [load(p) for p in sorted(glob.glob(os.path.join(label_dir, "*", "*", "run-*", "summary.json")))]
    runs = [r for r in runs if r]
    metas = [r["meta"] for r in runs]
    seed_path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(label_dir))), "data", "seed_counts.csv")
    seed = open(seed_path, encoding="utf-8").read().strip().splitlines() if os.path.exists(seed_path) else []
    slo_read, slo_heavy = plan.get("slo_read_ms", 500), plan.get("slo_heavy_ms", 1000)

    L = [f"# Y:ON 성능·부하 측정 결과 — {os.path.basename(os.path.normpath(label_dir))}", ""]
    start = min((m.get("boot_started") or m.get("k6_started") or 0 for m in metas if (m.get("boot_started") or m.get("k6_started"))), default=0)
    end = max((m.get("k6_ended") or 0 for m in metas), default=0)
    L += [f"측정 {ts(start)} ~ {ts(end)} (KST). Docker로 EC2 t3.small 4대를 모사한 환경의 결과이며, "
          "같은 환경 안의 개선 전·후 비교에만 쓴다.", ""]

    L += ["## 조건", "",
          "| 항목 | 값 |", "| --- | --- |",
          f"| 코드 | `{plan.get('branch', '-')}` @ `{plan.get('sha', '-')}` (origin/main `{plan.get('origin_main', '-')}`), 이미지 태그 `{plan.get('tag', '-')}` |",
          f"| Docker 호스트 | CPU {host.get('ncpu', '-')}개, 메모리 {round((host.get('mem_bytes') or 0) / 2**30, 1)}GiB, {host.get('os', '-')}, Docker {host.get('docker', '-')} |",
          f"| 코어 배치 | main {plan.get('cpuset', {}).get('main', '-')}, data {plan.get('cpuset', {}).get('data', '-')}, db {plan.get('cpuset', {}).get('db', '-')}, 측정용 {plan.get('cpuset', {}).get('tools', '-')} (복제본 {'사용' if plan.get('replica') == '1' else '미사용'}) |",
          f"| 부하 | 동시 접속 {plan.get('users', 100)}명 상당 (혼합 흐름: 비회원 40%·로그인 45%·저장 10%·알림 5%) |",
          f"| 기준값 | 조회 p95 < {slo_read}ms, 추천·판정 목록 p95 < {slo_heavy}ms, 실패율 < 1% |",
          f"| 회차 | 시나리오별 {plan.get('runs', '-')}회 (probe 3회) |",
          f"| 시드 | {', '.join(l.replace(',', ' ') for l in seed) if seed else '-'} |", ""]

    by_s = {}
    for r in runs:
        by_s.setdefault(r["meta"]["scenario"], []).append(r)
    k6_fail = [f"{m['scenario']}/run-{m['run']:02d}(exit {m['k6_exit']})" for m in metas if m.get("k6_exit") not in (0, None)]
    L += [f"전체 회차 {len(runs)}개. k6 종료 코드가 0이 아닌 회차(기준값 미달 포함): {', '.join(k6_fail) if k6_fail else '없음'}.", ""]

    L += ["## 기준값 판정 (측정 구간, 회차별 p95의 중앙값)", "",
          "| 시나리오 | 조회 p95 (ms) | 추천·판정 목록 p95 (ms) | 실패율 | 처리량 (req/s) | 판정 |", "| --- | --- | --- | --- | --- | --- |"]
    for scen, title in (("loadA", "부하 A: 사용자만"), ("loadB", "부하 B: 수집 동시")):
        rs = by_s.get(scen, [])
        if not rs:
            continue
        w = [r["windows"].get("main", {}) for r in rs]
        read, mr = med_range([(x.get("slo", {}).get("read") or {}).get("p95") for x in w])
        heavy, mh = med_range([(x.get("slo", {}).get("heavy") or {}).get("p95") for x in w])
        errs = [(x.get("slo", {}).get("all") or {}).get("err_rate") for x in w]
        err_m = statistics.median([e for e in errs if e is not None]) if any(e is not None for e in errs) else None
        rps, _ = med_range([x.get("rps") for x in w])
        ok = (mr is not None and mr < slo_read) and (mh is None or mh < slo_heavy) and (err_m is not None and err_m < 0.01)
        L.append(f"| {title} | {read} | {heavy} | {'-' if err_m is None else f'{err_m:.2%}'} | {rps} | {'충족' if ok else '미달'} |")
    if "loadB" in by_s:
        pre = med_range([(r["windows"].get("pre_collection", {}).get("slo", {}).get("read") or {}).get("p95") for r in by_s["loadB"]])[0]
        dur = med_range([(r["windows"].get("during_collection", {}).get("slo", {}).get("read") or {}).get("p95") for r in by_s["loadB"]])[0]
        cd = med_range([(r.get("collection") or {}).get("duration_s") for r in by_s["loadB"]])[0]
        nd = med_range([(r.get("collection") or {}).get("normalization_drained_s") for r in by_s["loadB"]])[0]
        L += ["", f"부하 B 조회 p95: 수집 전 {pre} → 수집 중 {dur}. 수집 소요 {cd}초, 정규화 대기 해소 {nd}초(수집 시작 기준)."]
    if "probe" in by_s:
        mp = med_range([(r.get("probe") or {}).get("max_passing_users") for r in by_s["probe"]])[0]
        L += ["", f"한계 탐색: 기준값을 지킨 최대 동시 접속 {mp}명 상당 (100명 단위)."]
    L.append("")

    # 서버 자원 (부하 A)
    if "loadA" in by_s:
        L += ["## 서버 자원 (부하 A 측정 구간, CPU는 코어 1개=100%, 서버당 최대 200%)", "",
              "| 컨테이너 | CPU 평균 | CPU p95 | 메모리 최대 (MiB) |", "| --- | --- | --- | --- |"]
        names = sorted({n for r in by_s["loadA"] for n in (r["windows"].get("main", {}).get("resources") or {})})
        for n in names:
            vals = [(r["windows"].get("main", {}).get("resources") or {}).get(n, {}) for r in by_s["loadA"]]
            L.append(f"| {n} | {med_range([v.get('cpu_avg') for v in vals])[0]} | {med_range([v.get('cpu_p95') for v in vals])[0]} | {med_range([v.get('mem_max_mib') for v in vals])[0]} |")
        dbw = [r["windows"].get("main", {}).get("db") or {} for r in by_s["loadA"]]
        L += ["", f"DB 활성 연결 최대 {med_range([d.get('active_max') for d in dbw])[0]}, 전체 연결 최대 {med_range([d.get('connections_max') for d in dbw])[0]}.", ""]

    # 느린 쿼리 (부하 A 첫 회차)
    first = sorted(glob.glob(os.path.join(label_dir, "loadA", "*", "run-*", "pg_stat_statements.tsv")))
    if first:
        L += ["## DB 총 실행 시간 상위 쿼리 (부하 A 1회차 전체 구간)", "",
              "| 총 ms | 호출 | 평균 ms | 최대 ms | 쿼리 (앞부분) |", "| --- | --- | --- | --- | --- |"]
        for line in open(first[0], encoding="utf-8").read().splitlines()[:10]:
            c = line.split("\t")
            if len(c) >= 9:
                q = c[8][:160].replace("|", "\\|")
                L.append(f"| {c[0]} | {c[1]} | {c[2]} | {c[3]} | `{q}` |")
        L.append("")

    L += ["## 상세 집계", "", "시나리오·API별 전체 표는 아래와 같다 (`report.md`와 같은 내용).", ""]
    rep = open(os.path.join(label_dir, "report.md"), encoding="utf-8").read().splitlines()
    L += [l.replace("## ", "### ", 1) if l.startswith("## ") else l for l in rep[5:]]
    L += ["", "## 파일", "",
          "- 회차별: `<시나리오>/<태그>/run-NN/` — `summary.json`, `k6.csv.gz`, `docker_stats.csv`, `pg_activity.csv`, `pg_stat_statements.tsv`, `jfr/*.jfr`, 서버 로그",
          "- 전체 값: `report.csv`, 실행 기록: `console.log`", "",
          "이 문서가 생성됐으면 Claude에게 '완료'라고 보내 주세요."]
    out = os.path.join(label_dir, "RESULT.md")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")
    print(f"결과 문서: {out}")


if __name__ == "__main__":
    main(sys.argv[1])
