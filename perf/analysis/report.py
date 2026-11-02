"""묶음(label) 하나의 회차별 summary.json 을 모아 집계표와 전후 비교를 만든다 (표준 라이브러리만 사용).

  python analysis/report.py results/<label>

출력: report.md (사람용), report.csv (지표 × 버전 × 회차, 긴 형식)
대표값은 회차별 값의 중앙값과 범위(최소~최대). 평균은 쓰지 않는다.
비교: 첫 번째 버전(정렬 순서가 아니라 --versions 첫 값; 없으면 이름순)을 기준으로
      변화율과 Mann–Whitney U 정확 검정(양측) p 값을 붙인다.
      '개선'은 버전별 4회 이상이고 p < 0.05 일 때만 쓴다.
"""
import csv
import glob
import itertools
import json
import os
import statistics
import sys
from collections import defaultdict

ORDER_FILE = ".versions"


def runs(label_dir):
    for path in sorted(glob.glob(os.path.join(label_dir, "*", "*", "run-*", "summary.json"))):
        parts = path.replace("\\", "/").split("/")
        yield parts[-4], parts[-3], int(parts[-2].split("-")[1]), json.load(open(path, encoding="utf-8"))


def g(d, *keys):
    for k in keys:
        if d is None:
            return None
        d = d.get(k) if isinstance(d, dict) else None
    return d


def metrics_of(scenario, s):
    """회차 하나에서 비교할 값들 (낮을수록 좋음: lower=True)"""
    m = {}
    W = s.get("windows", {})
    if scenario == "perf":
        for name, w in W.items():
            ep = name[3:]
            for k in ("p50", "p95"):
                v = g(w, "endpoints", ep, k)
                if v is None:  # save_unsave 처럼 두 API를 부르는 경우
                    for e2, st in (w.get("endpoints") or {}).items():
                        m[f"{e2}.{k}_ms (단독)"] = st[k] if st else None
                else:
                    m[f"{ep}.{k}_ms (단독)"] = v
        return m
    if scenario == "probe":
        p = s.get("probe", {})
        m["max_passing_users"] = p.get("max_passing_users")
        return m
    windows = ["main"] + (["pre_collection", "during_collection"] if scenario == "loadB" else [])
    for wn in windows:
        w = W.get(wn)
        if not w:
            continue
        pre = "" if wn == "main" else f"[{wn}] "
        m[f"{pre}read.p95_ms"] = g(w, "slo", "read", "p95")
        m[f"{pre}heavy.p95_ms"] = g(w, "slo", "heavy", "p95")
        m[f"{pre}error_rate"] = g(w, "slo", "all", "err_rate")
        if wn == "main":
            m["rps"] = w.get("rps")
            m["dropped_iterations"] = w.get("dropped_iterations")
            for ep, st in (w.get("endpoints") or {}).items():
                m[f"{ep}.p95_ms"] = st["p95"] if st else None
            for c in ("backend", "notification-service", "data-service", "db", "db-replica"):
                m[f"cpu_avg.{c}"] = g(w, "resources", c, "cpu_avg")
                m[f"mem_max_mib.{c}"] = g(w, "resources", c, "mem_max_mib")
            m["db.active_max"] = g(w, "db", "active_max")
            m["db.connections_max"] = g(w, "db", "connections_max")
    if scenario == "loadB":
        c = s.get("collection", {})
        m["collection.duration_s"] = c.get("duration_s")
        m["collection.normalization_drained_s"] = c.get("normalization_drained_s")
    return {k: v for k, v in m.items() if v is not None}


HIGHER_IS_BETTER = ("rps", "max_passing_users")


def mann_whitney_p(a, b):
    """정확 검정(양측). 동점은 평균 순위. n이 작을 때(≤10+10)만 쓴다."""
    n1, n2 = len(a), len(b)
    if n1 < 2 or n2 < 2 or n1 + n2 > 20:
        return None
    allv = sorted((v, i) for i, v in enumerate(a + b))
    ranks = [0.0] * (n1 + n2)
    i = 0
    while i < len(allv):
        j = i
        while j + 1 < len(allv) and allv[j + 1][0] == allv[i][0]:
            j += 1
        r = (i + j) / 2 + 1
        for k in range(i, j + 1):
            ranks[allv[k][1]] = r
        i = j + 1
    obs = sum(ranks[:n1])
    mean = n1 * (n1 + n2 + 1) / 2
    dev = abs(obs - mean)
    total = hits = 0
    for combo in itertools.combinations(range(n1 + n2), n1):
        total += 1
        if abs(sum(ranks[c] for c in combo) - mean) >= dev - 1e-9:
            hits += 1
    return hits / total


def fmt(v):
    if v is None:
        return "-"
    if isinstance(v, float):
        return f"{v:.3f}" if abs(v) < 1 else f"{v:,.1f}"
    return str(v)


def main(label_dir):
    data = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))  # scenario → metric → version → [values]
    rows = []
    versions_seen = []
    for scenario, version, run, s in runs(label_dir):
        if version not in versions_seen:
            versions_seen.append(version)
        for k, v in metrics_of(scenario, s).items():
            data[scenario][k][version].append(v)
            rows.append([scenario, k, version, run, v])
    order_path = os.path.join(label_dir, ORDER_FILE)
    versions = open(order_path).read().split(",") if os.path.exists(order_path) else sorted(versions_seen)
    versions = [v.strip() for v in versions if v.strip() in versions_seen]

    with open(os.path.join(label_dir, "report.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["scenario", "metric", "version", "run", "value"])
        w.writerows(rows)

    lines = [f"# 측정 결과: {os.path.basename(label_dir.rstrip('/'))}", "",
             "값은 회차별 값의 **중앙값 (최소~최대, n)**. 비교 기준은 첫 버전 `" + (versions[0] if versions else "-") + "`.",
             "판정: 버전별 회차가 4회 이상이고 Mann–Whitney 정확 검정 p<0.05 이면 개선/악화, 아니면 '차이 확인 못 함'.",
             "(회차 5회 이상에서 두 범위가 겹치지 않으면 p<0.05 가 된다.)", ""]
    names = {"perf": "성능(API 단독)", "loadA": "부하 A (사용자만)", "loadB": "부하 B (수집 동시)", "probe": "한계 탐색"}
    for scenario in ("perf", "probe", "loadA", "loadB"):
        if scenario not in data:
            continue
        lines += [f"## {names[scenario]}", ""]
        head = ["지표"] + [f"{v}" for v in versions]
        if len(versions) > 1:
            head += ["변화율", "p", "판정"]
        lines += ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
        for metric in sorted(data[scenario]):
            per = data[scenario][metric]
            cells = [metric]
            for v in versions:
                vals = per.get(v, [])
                cells.append(f"{fmt(statistics.median(vals))} ({fmt(min(vals))}~{fmt(max(vals))}, {len(vals)})"
                             if vals else "-")
            if len(versions) > 1:
                a, b = per.get(versions[0], []), per.get(versions[-1], [])
                if a and b:
                    ma, mb = statistics.median(a), statistics.median(b)
                    change = (mb - ma) / ma * 100 if ma else None
                    p = mann_whitney_p(a, b)
                    better = (mb > ma) if metric.split(".")[0] in HIGHER_IS_BETTER or metric in HIGHER_IS_BETTER else (mb < ma)
                    if p is None or min(len(a), len(b)) < 4:
                        verdict = "회차 부족"
                    elif p < 0.05 and ma != mb:
                        verdict = "개선" if better else "악화"
                    else:
                        verdict = "차이 확인 못 함"
                    cells += [f"{change:+.1f}%" if change is not None else "-", fmt(p), verdict]
                else:
                    cells += ["-", "-", "-"]
            lines.append("| " + " | ".join(cells) + " |")
        lines.append("")
    with open(os.path.join(label_dir, "report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("\n".join(lines[:60]))


if __name__ == "__main__":
    main(sys.argv[1])
