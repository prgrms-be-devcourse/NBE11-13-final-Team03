"""회차 하나를 집계해 summary.json 을 만든다 (표준 라이브러리만 사용).

  python analysis/summarize.py results/<label>/<scenario>/<version>/run-01

입력: meta.json, k6.csv.gz(요청별 기록), docker_stats.csv, pg_activity.csv
구간: perf → API별 ep_* 시나리오 / loadA → main / loadB → main 전체 + 수집 전·중·후 / probe → 단계별 step_*
"""
import csv
import gzip
import json
import math
import os
import sys
from collections import defaultdict


def pct(values, p):
    if not values:
        return None
    v = sorted(values)
    k = (len(v) - 1) * p / 100
    lo, hi = math.floor(k), math.ceil(k)
    return v[lo] if lo == hi else v[lo] + (v[hi] - v[lo]) * (k - lo)


def tags_of(extra):
    out = {}
    for part in (extra or "").split("&"):
        if "=" in part:
            k, v = part.split("=", 1)
            out[k] = v
    return out


def stats(durations, failures):
    n = len(durations)
    if not n:
        return None
    return {"count": n, "p50": round(pct(durations, 50), 2), "p95": round(pct(durations, 95), 2),
            "p99": round(pct(durations, 99), 2), "mean": round(sum(durations) / n, 2),
            "err_rate": round(failures / n, 5)}


def load_k6(path):
    reqs, iters, dropped, events, vus = [], [], [], [], []
    with gzip.open(path, "rt", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            m = row["metric_name"]
            ts = int(float(row["timestamp"]))
            t = tags_of(row.get("extra_tags"))
            if m == "http_req_duration":
                reqs.append({"ts": ts, "ms": float(row["metric_value"]), "scenario": row["scenario"],
                             "ok": row["expected_response"] == "true", "ep": t.get("ep", row["name"]),
                             "slo": t.get("slo", ""), "status": row["status"]})
            elif m == "iterations":
                iters.append((ts, row["scenario"]))
            elif m == "dropped_iterations":
                dropped.append((ts, row["scenario"], float(row["metric_value"])))
            elif m == "collection_event":
                events.append((ts, t.get("event")))
            elif m == "vus":
                vus.append((ts, float(row["metric_value"])))
    return reqs, iters, dropped, events, vus


def window_of(reqs, scenario):
    ts = [r["ts"] for r in reqs if r["scenario"] == scenario]
    return [min(ts), max(ts) + 1] if ts else None


def summarize_requests(reqs, iters, dropped, window, scenario=None):
    t0, t1 = window
    sel = [r for r in reqs if t0 <= r["ts"] < t1 and (scenario is None or r["scenario"] == scenario)
           and r["slo"] != "admin"]
    by_ep, by_slo = defaultdict(list), defaultdict(list)
    fail_ep, fail_slo = defaultdict(int), defaultdict(int)
    for r in sel:
        by_ep[r["ep"]].append(r["ms"])
        by_slo[r["slo"]].append(r["ms"])
        by_slo["all"].append(r["ms"])
        if not r["ok"]:
            fail_ep[r["ep"]] += 1
            fail_slo[r["slo"]] += 1
            fail_slo["all"] += 1
    secs = max(1, t1 - t0)
    it = [x for x in iters if t0 <= x[0] < t1 and (scenario is None or x[1] == scenario)]
    dr = sum(v for ts, s, v in dropped if t0 <= ts < t1 and (scenario is None or s == scenario))
    return {
        "window": [t0, t1], "seconds": secs,
        "endpoints": {ep: stats(v, fail_ep[ep]) for ep, v in sorted(by_ep.items())},
        "slo": {k: stats(v, fail_slo[k]) for k, v in by_slo.items()},
        "rps": round(len(sel) / secs, 2),
        "visits_per_s": round(len(it) / secs, 3),
        "dropped_iterations": dr,
    }


def mem_mib(text):
    text = text.strip()
    units = {"KiB": 1 / 1024, "MiB": 1, "GiB": 1024, "kB": 1 / 1000, "MB": 1, "GB": 1000, "B": 1 / 1048576}
    for u, f in units.items():
        if text.endswith(u):
            try:
                return float(text[: -len(u)]) * f
            except ValueError:
                return None
    return None


def load_docker_stats(path):
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            try:
                name = r["container"].replace("yon-perf-", "").rsplit("-", 1)[0]
                rows.append((int(r["ts"]), name, float(r["cpu_pct"]), mem_mib(r["mem_used"])))
            except (ValueError, KeyError, TypeError):
                continue
    return rows


def resources(stats_rows, window):
    t0, t1 = window
    by = defaultdict(lambda: {"cpu": [], "mem": []})
    for ts, name, cpu, mem in stats_rows:
        if t0 <= ts < t1:
            by[name]["cpu"].append(cpu)
            if mem is not None:
                by[name]["mem"].append(mem)
    out = {}
    for name, v in sorted(by.items()):
        out[name] = {"cpu_avg": round(sum(v["cpu"]) / len(v["cpu"]), 1) if v["cpu"] else None,
                     "cpu_p95": round(pct(v["cpu"], 95), 1) if v["cpu"] else None,
                     "mem_max_mib": round(max(v["mem"]), 0) if v["mem"] else None}
    return out


def load_pg(path):
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            try:
                rows.append({"ts": int(r["ts"]), "active": int(r["active"]), "lock": int(r["waiting_lock"]),
                             "idle_tx": int(r["idle_in_tx"]), "total": int(r["total"]),
                             "pending": int(r["pending_normalization"] or 0), "wait": r.get("top_wait", "")})
            except (ValueError, KeyError):
                continue
    return rows


def db_window(pg_rows, window):
    t0, t1 = window
    sel = [r for r in pg_rows if t0 <= r["ts"] < t1]
    if not sel:
        return None
    waits = defaultdict(int)
    for r in sel:
        if r["wait"]:
            waits[r["wait"]] += 1
    return {"active_avg": round(sum(r["active"] for r in sel) / len(sel), 2),
            "active_max": max(r["active"] for r in sel), "lock_wait_max": max(r["lock"] for r in sel),
            "connections_max": max(r["total"] for r in sel), "pending_max": max(r["pending"] for r in sel),
            "top_waits": dict(sorted(waits.items(), key=lambda x: -x[1])[:5])}


def main(run_dir):
    meta = json.load(open(os.path.join(run_dir, "meta.json"), encoding="utf-8"))
    reqs, iters, dropped, events, vus = load_k6(os.path.join(run_dir, "k6.csv.gz"))
    stats_rows = load_docker_stats(os.path.join(run_dir, "docker_stats.csv"))
    pg_rows = load_pg(os.path.join(run_dir, "pg_activity.csv"))
    scenario = meta["scenario"]
    out = {"meta": meta, "windows": {}}

    def add(name, window, scen=None):
        if not window:
            return
        w = summarize_requests(reqs, iters, dropped, window, scen)
        w["resources"] = resources(stats_rows, window)
        w["db"] = db_window(pg_rows, window)
        out["windows"][name] = w

    if scenario == "perf":
        for s in sorted({r["scenario"] for r in reqs if r["scenario"].startswith("ep_")}):
            add(s, window_of(reqs, s), s)
    elif scenario == "probe":
        steps = []
        for s in sorted({r["scenario"] for r in reqs if r["scenario"].startswith("step_")},
                        key=lambda x: int(x.split("_")[1])):
            add(s, window_of(reqs, s), s)
            w = out["windows"][s]
            steps.append({"users": int(s.split("_")[1]), "read_p95": (w["slo"].get("read") or {}).get("p95"),
                          "heavy_p95": (w["slo"].get("heavy") or {}).get("p95"),
                          "err_rate": (w["slo"].get("all") or {}).get("err_rate"), "rps": w["rps"],
                          "dropped": w["dropped_iterations"]})
        slo_read = float(os.environ.get("SLO_READ_P95_MS", "500"))
        slo_heavy = float(os.environ.get("SLO_HEAVY_P95_MS", "1000"))
        knee, last_ok = None, None
        for st in steps:
            bad = ((st["read_p95"] or 0) > slo_read or (st["heavy_p95"] or 0) > slo_heavy
                   or (st["err_rate"] or 0) > 0.01 or st["dropped"] > 0)
            if bad and knee is None:
                knee = st["users"]
            if not bad and knee is None:
                last_ok = st["users"]
        out["probe"] = {"steps": steps, "first_failing_users": knee, "max_passing_users": last_ok}
    else:
        main_w = window_of(reqs, "main")
        add("main", main_w, "main")
        if scenario == "loadB" and main_w:
            starts = [ts for ts, e in events if e == "start"]
            ends = [ts for ts, e in events if e == "end"]
            if starts:
                cs = min(starts)
                ce = min(ends) if ends else main_w[1]
                add("pre_collection", [main_w[0], cs], "main")
                add("during_collection", [cs, min(ce, main_w[1])], "main")
                if ce < main_w[1]:
                    add("post_collection", [ce, main_w[1]], "main")
                pend = [r for r in pg_rows if r["ts"] >= cs]
                drained = next((r["ts"] for r in pend if r["ts"] > ce and r["pending"] == 0), None)
                out["collection"] = {"start": cs, "end": ends[0] if ends else None,
                                     "duration_s": (ends[0] - cs) if ends else None,
                                     "normalization_drained_s": (drained - cs) if drained else None,
                                     "pending_max": max((r["pending"] for r in pend), default=None)}
    out["vus_max"] = max((v for _, v in vus), default=None)
    with open(os.path.join(run_dir, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"summary written: {run_dir} windows={list(out['windows'])}")


if __name__ == "__main__":
    main(sys.argv[1])
