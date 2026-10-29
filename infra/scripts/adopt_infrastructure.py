"""Collect and verify the whole adoption plan; --apply registers only no-change imports."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

from check_import_plan import check
from collect_inventory import Collector
from prepare_import import prepare

ROOT = Path(__file__).resolve().parents[2]


def write_json(path, value):
    path.touch(mode=0o600, exist_ok=False)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def execute(state_dir, apply=False):
    if not state_dir.is_absolute() or ROOT in state_dir.resolve().parents or state_dir.resolve() == ROOT:
        raise ValueError("Use an absolute state directory outside the checkout")
    state_dir = state_dir.resolve()
    state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    backend = ROOT / "infra/terraform/.terraform/terraform.tfstate"
    if backend.exists():
        metadata = json.loads(backend.read_text(encoding="utf-8"))["backend"]
        configured = metadata["config"].get("path")
        if metadata["type"] != "local" or not configured or Path(configured).resolve() != state_dir / "terraform.tfstate":
            raise ValueError("Existing backend differs; the state owner must review it before initialization")
    tool = shutil.which("terraform")
    if not tool:
        raise ValueError("Install Terraform 1.17.0 before running adoption")
    run = state_dir / ("adoption-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ"))
    run.mkdir(mode=0o700)

    def terraform(args, output):
        log = run / output
        with log.open("xb") as stdout, log.with_name(log.name + ".stderr.log").open("xb") as stderr:
            result = subprocess.run([tool, "-chdir=infra/terraform", *args], cwd=ROOT,
                                    stdout=stdout, stderr=stderr, env={**os.environ, "TF_IN_AUTOMATION": "1", "TF_INPUT": "0"})
        if result.returncode:
            raise ValueError(f"Terraform {args[0]} failed; review private logs in {run}")
        return log

    version = json.loads(terraform(["version", "-json"], "version.json").read_text(encoding="utf-8"))
    if not version["terraform_version"].startswith("1.17."):
        raise ValueError("Terraform 1.17.x is required")
    inventory_dir = run / "inventory"
    collector = Collector()
    if not collector.collect(inventory_dir, "import"):
        failed = [k for k, v in collector.results.items() if v["status"] == "error"]
        raise ValueError("Incomplete inventory; review private inventory.json errors: " + ", ".join(failed))
    inputs, targets = prepare(inventory_dir / "inventory.json")
    variables = run / "adoption.tfvars.json"
    write_json(variables, {"adoption": inputs})
    write_json(run / "targets.json", targets)
    terraform(["init", "-input=false", "-lockfile=readonly", f"-backend-config=path={state_dir / 'terraform.tfstate'}"], "init.log")
    terraform(["validate", "-no-color"], "validate.log")
    state = state_dir / "terraform.tfstate"
    if state.exists():
        shutil.copyfile(state, run / "before.tfstate")

    def plan(name, phase):
        saved = run / (name + ".tfplan")
        terraform(["plan", "-input=false", "-lock-timeout=60s", "-no-color", f"-var-file={variables}", f"-out={saved}"], name + ".log")
        data = json.loads(terraform(["show", "-json", str(saved)], name + ".json").read_text(encoding="utf-8"))
        check(data, phase, targets)
        pending = sum(bool(x["change"].get("importing")) for x in data["resource_changes"])
        print(f"PASS: {len(targets)} targets, {pending} pending imports, 0 AWS resource changes ({phase})", flush=True)
        return saved, pending

    saved, pending = plan("import-plan", "import")
    digest = hashlib.sha256(saved.read_bytes()).hexdigest()
    write_json(run / "review.json", {"target_count": len(targets), "pending_imports": pending, "aws_resource_changes": 0,
                                     "plan_sha256": digest, "terraform_version": version["terraform_version"]})
    if apply:
        if hashlib.sha256(saved.read_bytes()).hexdigest() != digest:
            raise ValueError("Saved plan changed after verification")
        if pending:
            terraform(["apply", "-input=false", "-lock-timeout=60s", "-no-color", str(saved)], "apply.log")
        if not state.exists():
            raise ValueError("The imported state file is missing")
        shutil.copyfile(state, run / "after.tfstate")
        plan("after-plan", "after")
        write_json(run / "completed.json", {"target_count": len(targets), "aws_resource_changes": 0,
                                            "completed_at": datetime.now(timezone.utc).isoformat()})
        print("State registration and post-import verification completed.")
    else:
        print("Plan verified. Re-run with --apply to collect a fresh plan and register it after checks.")
    print(f"Private run directory: {run}")
    return run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--apply", action="store_true", help="Import only after the complete no-change plan passes; verify again afterward")
    args = parser.parse_args()
    previous_umask = os.umask(0o077)
    try:
        execute(args.state_dir, args.apply)
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        parser.exit(1, f"STOP: {error}\n")
    finally:
        os.umask(previous_umask)


if __name__ == "__main__":
    main()
