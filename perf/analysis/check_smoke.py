"""스모크 결과 점검: 측정 도구와 서버가 제대로 맞물리는지 본다.

  python analysis/check_smoke.py results/smoke      (실패하면 종료 코드 1)

- perf(API를 하나씩 낮은 부하로): 모든 API의 실패율·체크 실패율이 낮아야 통과 → 인증·CSRF·데이터 문제 없음
- loadA·loadB·probe: 요청이 실제로 나갔는지만 본다. 부하에서 생긴 실패(커넥션 풀 고갈 등)는 측정할 결과이므로 막지 않는다.
"""
import csv
import glob
import gzip
import json
import os
import sys
from collections import Counter

MAX_ERROR = 0.02


def failing_statuses(run_dir):
    c = Counter()
    path = os.path.join(run_dir, "k6.csv.gz")
    if not os.path.exists(path):
        return c
    with gzip.open(path, "rt", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["metric_name"] == "http_req_duration" and row["expected_response"] != "true":
                ep = next((p.split("=", 1)[1] for p in (row.get("extra_tags") or "").split("&") if p.startswith("ep=")),
                          row["name"])
                c[(ep, row["status"] or "no-response")] += 1
    return c


def checks_rate(run_dir):
    try:
        s = json.load(open(os.path.join(run_dir, "k6-summary.json"), encoding="utf-8"))
        m = s["metrics"]["checks"]
        passes, fails = m.get("passes", 0), m.get("fails", 0)
        return fails / max(1, passes + fails), passes + fails
    except (OSError, KeyError, ValueError):
        return None, 0


def main(base):
    ok = True
    rows = []
    for summary in sorted(glob.glob(os.path.join(base, "*", "*", "run-*", "summary.json"))):
        run_dir = os.path.dirname(summary)
        s = json.load(open(summary, encoding="utf-8"))
        scen = s["meta"]["scenario"]
        total = err = 0
        for w in s["windows"].values():
            a = (w.get("slo") or {}).get("all") or {}
            total += a.get("count", 0)
            err += a.get("count", 0) * a.get("err_rate", 0)
        err_rate = err / total if total else 1.0
        chk, n_chk = checks_rate(run_dir)
        problems, notes = [], []
        if total == 0:
            problems.append("측정 구간 요청 0건")
        target = problems if scen == "perf" else notes
        if err_rate > MAX_ERROR:
            target.append(f"실패율 {err_rate:.1%}")
        if chk is not None and chk > MAX_ERROR:
            target.append(f"체크 실패율 {chk:.1%}")
        if scen == "loadB":
            c = s.get("collection") or {}
            if not c.get("duration_s"):
                notes.append("수집이 측정 구간 안에 끝나지 않음")
        if problems:
            ok = False
        verdict = "; ".join(problems) if problems else ("OK" + (f" (부하 결과: {'; '.join(notes)})" if notes else ""))
        rows.append((scen, total, err_rate, chk, verdict, run_dir))
    if not rows:
        print("스모크 결과(summary.json)가 없습니다. results/smoke/*/*/run-01/summarize.log 와 k6.log 를 확인하세요")
        return 1
    print(f"{'시나리오':8} {'요청':>7} {'실패율':>7} {'체크실패':>8}  판정")
    for scen, total, er, chk, verdict, _ in rows:
        print(f"{scen:8} {total:>7} {er:>7.1%} {('-' if chk is None else f'{chk:.1%}'):>8}  {verdict}")
    if not ok or any("부하 결과" in r[4] for r in rows):
        print("\n실패한 요청 (API, 상태 코드: 건수):")
        for scen, *_, run_dir in rows:
            c = failing_statuses(run_dir)
            for (ep, st), n in c.most_common(8):
                print(f"  {scen:6} {ep:24} {st:>12}: {n}")
        print("\n서버 로그: results/smoke/<시나리오>/<태그>/run-01/backend.log, data-service.log, notification-service.log")
    if not ok:
        return 1
    print("\n스모크 점검 통과")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
