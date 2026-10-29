"""Verify adoption; --apply registers no-change imports or declared state moves."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

from check_import_plan import (check, require_cloudfront_target, MAIN_HTTP_SOURCE, MAIN_HTTP_TARGET,
                               MAIN_HTTP_OLD_ID, MAIN_HTTP_NEW_ID)
from collect_inventory import Collector
from prepare_import import prepare

ROOT = Path(__file__).resolve().parents[2]


def write_json(path, value):
    path.touch(mode=0o600, exist_ok=False)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main_http_rebind(state, targets):
    """Identify only the operator-reported stale flat binding; never edit raw state."""
    require_cloudfront_target(targets)
    if not state.exists():
        raise ValueError("Rebinding requires the existing imported state")
    objects = {}
    for resource in json.loads(state.read_text(encoding="utf-8")).get("resources", []):
        if resource.get("mode") != "managed" or resource.get("type") != "aws_vpc_security_group_ingress_rule":
            continue
        for item in resource.get("instances", []):
            if "deposed" in item:
                raise ValueError("Deposed ingress state requires review")
            address = (resource.get("module", "") + "." if resource.get("module") else "")
            address += resource["type"] + "." + resource["name"]
            if "index_key" in item:
                address += "[" + json.dumps(item["index_key"]) + "]"
            if address in objects:
                raise ValueError("Duplicate ingress state requires review")
            objects[address] = item.get("attributes", {})
    old, current = objects.get(MAIN_HTTP_SOURCE), objects.get(MAIN_HTTP_TARGET)
    if old and current:
        raise ValueError("Both main HTTP bindings exist; state owner review required")
    if (old or current or {}).get("id") == MAIN_HTTP_NEW_ID:
        return None
    expected = targets[MAIN_HTTP_TARGET]["values"]
    if (not old or old.get("id") != MAIN_HTTP_OLD_ID or current
            or old.get("security_group_id") != expected["security_group_id"]
            or old.get("cidr_ipv4") != "0.0.0.0/0" or old.get("prefix_list_id")
            or old.get("referenced_security_group_id") or old.get("ip_protocol") != "tcp"
            or old.get("from_port") != 8080 or old.get("to_port") != 8080):
        raise ValueError("Only the approved stale flat main HTTP binding may be forgotten")
    return {"address": MAIN_HTTP_SOURCE, "old_id": MAIN_HTTP_OLD_ID, "new_id": MAIN_HTTP_NEW_ID}


def execute(state_dir, apply=False, rebind_main_http=False):
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

    rebinding = main_http_rebind(state, targets) if rebind_main_http else None
    preview = None
    if rebinding:
        preview = run / "rebind-preview.tfstate"
        shutil.copyfile(state, preview)
        terraform(["state", "rm", "-lock-timeout=60s", f"-state={preview}", rebinding["address"]], "rebind-preview.log")
        write_json(run / "rebind.json", {**rebinding, "actual_state_modified": False})
        print("Approved main HTTP state rebind: 1 forget / 1 import (preview copy only)", flush=True)

    def plan(name, phase, state_override=None, rebinding=False):
        saved = run / (name + ".tfplan")
        arguments = ["plan", "-input=false", "-lock-timeout=60s", "-no-color", f"-var-file={variables}", f"-out={saved}"]
        if state_override:
            arguments.append(f"-state={state_override}")
        terraform(arguments, name + ".log")
        data = json.loads(terraform(["show", "-json", str(saved)], name + ".json").read_text(encoding="utf-8"))
        check(data, phase, targets, rebind_main_http=bool(rebinding))
        pending = sum(bool(x["change"].get("importing")) for x in data["resource_changes"])
        moved = sum(bool(x.get("previous_address")) for x in data["resource_changes"])
        print(f"PASS: {len(targets)} targets, {pending} pending imports, 0 AWS resource changes ({phase})", flush=True)
        print(f"Verified state address moves: {moved}", flush=True)
        return saved, pending, moved

    saved, pending, moved = plan("import-plan", "import", preview, rebinding)
    if apply and rebinding:
        # Validate the whole preview before forgetting anything in the real state.
        # A concurrent state write invalidates approval; never restore a stale backup automatically.
        if state.read_bytes() != (run / "before.tfstate").read_bytes():
            raise ValueError("State changed during preview; re-run after owner review")
        if main_http_rebind(state, targets) != rebinding:
            raise ValueError("Main HTTP binding changed during preview")
        terraform(["state", "rm", "-lock-timeout=60s", rebinding["address"]], "rebind.log")
        write_json(run / "rebind-applied.json", {**rebinding, "actual_state_modified": True})
        saved, pending, moved = plan("rebind-plan", "import", rebinding=rebinding)
    digest = hashlib.sha256(saved.read_bytes()).hexdigest()
    write_json(run / "review.json", {"target_count": len(targets), "pending_imports": pending, "address_moves": moved, "aws_resource_changes": 0,
                                     "plan_sha256": digest, "terraform_version": version["terraform_version"],
                                     "state_rebind": rebinding, "state_forgets": int(bool(rebinding))})
    if apply:
        if hashlib.sha256(saved.read_bytes()).hexdigest() != digest:
            raise ValueError("Saved plan changed after verification")
        if pending or moved:
            terraform(["apply", "-input=false", "-lock-timeout=60s", "-no-color", str(saved)], "apply.log")
        if not state.exists():
            raise ValueError("The imported state file is missing")
        shutil.copyfile(state, run / "after.tfstate")
        plan("after-plan", "after")
        write_json(run / "completed.json", {"target_count": len(targets), "aws_resource_changes": 0, "address_moves": 0,
                                            "completed_at": datetime.now(timezone.utc).isoformat()})
        print("State registration/address migration and post-plan verification completed.")
    else:
        print("Plan verified. Re-run with --apply to collect a fresh plan and register it after checks.")
    print(f"Private run directory: {run}")
    return run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--rebind-main-http", action="store_true",
                        help="Preview the approved stale 8080 state rebind; actual state rm requires --apply")
    parser.add_argument("--apply", action="store_true", help="Apply verified imports or declared state moves without AWS changes; verify again afterward")
    args = parser.parse_args()
    previous_umask = os.umask(0o077)
    try:
        execute(args.state_dir, args.apply, args.rebind_main_http)
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        parser.exit(1, f"STOP: {error}\n")
    finally:
        os.umask(previous_umask)


if __name__ == "__main__":
    main()
