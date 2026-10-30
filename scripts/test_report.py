"""Report actual executions; missing reports, required cases and unexpected skips fail closed."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
POLICY = ROOT / "scripts/test-policy.json"


def command(*args):
    try:
        result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=15)
        return (result.stdout or result.stderr).strip() if result.returncode == 0 else "unavailable"
    except (OSError, subprocess.TimeoutExpired):
        return "unavailable"


def source_inventory(service):
    if service == "frontend":
        return {Path(name).name: "fast" for name in command("git", "ls-files", "frontend/tests/*.test.mjs").splitlines()}
    inventory = {}
    for name in command("git", "ls-files", f"{service}/src/test/java").splitlines():
        source = (ROOT / name).read_text(encoding="utf-8")
        if not re.search(r"@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b", source) or "abstract class " in source:
            continue
        package = re.search(r"package\s+([\w.]+);", source).group(1)
        tags = set(re.findall(r'@Tag\(\s*"([^"]+)"\s*\)', source))
        group = "manual" if "manual" in tags else "postgres" if "postgres" in tags else "fast"
        inventory[package + "." + Path(name).stem] = group
    return inventory


def junit_report(paths, inventory, manual, required, browser=()):
    groups, skipped, problems, found, passed = {}, [], [], set(), set()
    cases = []
    for path in paths:
        try:
            # Node's testsuites wrapper is deliberately not counted as another suite.
            cases.extend(ET.parse(path).getroot().iter("testcase"))
        except (ET.ParseError, OSError) as error:
            problems.append(f"Unreadable report {path}: {error}")
    if not cases:
        problems.append("No executed test cases/reports")
    for case in cases:
        cls, name = case.get("classname", ""), case.get("name", "")
        owner = case.get("file", "").replace("\\", "/").rsplit("/", 1)[-1] if case.get("file") else cls.split("$")[0]
        found.add(owner)
        group = "browser" if name in browser else inventory.get(owner, "fast")
        counts = groups.setdefault(group, Counter(tests=0, passed=0, failed=0, skipped=0))
        counts["tests"] += 1
        skip = case.find("skipped")
        if skip is not None:
            counts["skipped"] += 1
            reason = skip.get("message") or skip.text or manual.get(owner, "unspecified by test runner")
            skipped.append({"class": cls, "case": name, "reason": reason})
            if owner not in manual:
                problems.append(f"Unexpected skip: {cls}.{name}")
        elif case.find("failure") is not None or case.find("error") is not None:
            counts["failed"] += 1
            problems.append(f"Failed: {cls}.{name}")
        else:
            counts["passed"] += 1
            passed.add((owner, name.split("(")[0]))
    for cls in (inventory.keys() | required.keys()) - found:
        problems.append(f"Test class did not execute: {cls}")
    for cls, methods in required.items():
        for method in methods:
            if (cls, method) not in passed:
                problems.append(f"Required scenario did not pass: {cls}.{method}")
    for name in browser:
        if not any(case.get("name") == name for case in cases):
            problems.append(f"Required browser scenario missing: {name}")
    return groups, skipped, problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", choices=("backend", "data-service", "notification-service", "frontend", "replica", "service-e2e"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--results", type=Path, help="Explicit XML directory or E2E results.json")
    args = parser.parse_args()
    policy = json.loads(POLICY.read_text(encoding="utf-8"))
    required = policy.get(args.service, {})
    if args.service == "service-e2e":
        result = args.results or ROOT / ".validation/service-e2e/results.json"
        rows = json.loads(result.read_text(encoding="utf-8")) if result.exists() else []
        groups = {"service-e2e": Counter(tests=len(rows), passed=sum(r["status"] == "passed" for r in rows), failed=sum(r["status"] != "passed" for r in rows), skipped=0)}
        skipped = []
        problems = [] if len(rows) == len(policy["e2eScenarios"]) and {r["scenario"] for r in rows} == set(policy["e2eScenarios"]) and all(r["status"] == "passed" for r in rows) else ["Service E2E did not pass every required scenario"]
    else:
        default = "backend/build/test-results/replicaTest" if args.service == "replica" else f"{args.service}/build/test-results/test"
        directory = args.results or ROOT / default
        inventory = {"com.team3.backend.global.config.ReplicaIntegrationTest": "replica"} if args.service == "replica" else source_inventory(args.service)
        paths = sorted(directory.glob("*.xml"))
        if args.service == "replica":
            for name in ("ReadReplicaRoutingDataSourceTest", "ReadReplicaDataSourceConfigTest"):
                cls = "com.team3.backend.global.config." + name
                inventory[cls] = "routing-unit"
                paths.append(ROOT / f"backend/build/test-results/test/TEST-{cls}.xml")
        groups, skipped, problems = junit_report(paths, inventory, policy["manual"], required, policy["browser"] if args.service == "frontend" else ())
        if args.service == "replica":
            checkpoint_file = ROOT / "backend/build/replica-evidence/checkpoints.json"
            checkpoints = json.loads(checkpoint_file.read_text()) if checkpoint_file.exists() else []
            if set(checkpoints) != set(policy["replicaCheckpoints"]):
                problems.append("Replica lifecycle is missing required checkpoints")
    tracked = command("git", "ls-files", "database/scripts", "*/src/test/resources", "*/src/replicaTest/resources").splitlines()
    hashes = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in tracked if (ROOT / name).is_file()}
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    environment = {
        "os": platform.platform(),
        "python": platform.python_version(),
        "java": command(java, "-version"),
        "node": command("node", "--version"),
        "docker": command("docker", "version", "--format", "{{.Server.Version}}"),
        "postgresImage": command("docker", "image", "inspect", "postgres:16-alpine", "--format", "{{.Id}} {{json .RepoDigests}}"),
        "localstackImage": command("docker", "image", "inspect", "localstack/localstack:4.12.0", "--format", "{{.Id}} {{json .RepoDigests}}"),
        "chrome": os.environ.get("CHROME_PATH", "not required by this group"),
        "fixtureSha256": hashes,
        "clockAndSeed": "Versioned per-test fixtures and clock/seed declarations; no global override",
        "testOrder": "Existing JUnit method-order declarations; Node file concurrency=1; no new random order",
        "workingTreeChanges": command("git", "diff", "--name-only").splitlines(),
        "policySha256": hashlib.sha256(POLICY.read_bytes()).hexdigest(),
    }
    report = {
        "service": args.service,
        "revision": command("git", "rev-parse", "HEAD"),
        "status": "failed" if problems else "passed",
        "groups": groups,
        "skippedCases": skipped,
        "violations": problems,
        "environment": environment,
    }
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = [f"### {args.service}: {report['status']}", "", "| Group | Executions | Passed | Failed | Skipped |", "|---|---:|---:|---:|---:|"]
    lines += [f"| {name} | {c['tests']} | {c['passed']} | {c['failed']} | {c['skipped']} |" for name, c in groups.items()]
    lines += ["", "Skips (excluded from passed):"] + [f"- {s['class']}.{s['case']}: {s['reason']}" for s in skipped]
    lines += ["", "Violations:"] + [f"- {problem}" for problem in problems]
    markdown = "\n".join(lines) + "\n"
    (args.output / "summary.md").write_text(markdown, encoding="utf-8")
    print(markdown)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as destination:
            destination.write(markdown)
    return bool(problems)


if __name__ == "__main__":
    raise SystemExit(main())
