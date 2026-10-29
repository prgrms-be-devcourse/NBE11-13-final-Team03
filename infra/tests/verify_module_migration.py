"""Offline native Terraform check: synthetic flat state -> modules -> no changes.

Only mock-plan values seed the state. Refresh is disabled and every service
endpoint is an unused loopback port, so this never queries operating AWS assets.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "infra/scripts"))
from check_import_plan import ADDRESS_MOVES, check
from prepare_import import prepare
sys.path.insert(0, str(ROOT / "infra/tests"))
from import_fixture import make_inventory


def verify(mock_log, terraform):
    plans = [r["test_plan"] for line in mock_log.read_text(encoding="utf-8").splitlines()
             if (r := json.loads(line)).get("type") == "test_plan"]
    assert len(plans) == 1, "Use the root import mock test output"
    plan = plans[0]
    moves = json.loads(ADDRESS_MOVES.read_text())
    previous = {new: old for old, new in moves.items()}
    schemas = plan["provider_schemas"]["registry.terraform.io/hashicorp/aws"]["resource_schemas"]
    resources = {}
    for resource in plan["resource_changes"]:
        assert resource["change"]["actions"] == ["no-op"]
        address = previous.get(resource["address"], resource["address"])
        match = re.fullmatch(r"([^.]+)\.([^\[]+)(?:\[(.+)\])?", address)
        assert match, address
        kind, name, index = match.groups()
        group = resources.setdefault((kind, name), {
            "mode": "managed", "type": kind, "name": name,
            "provider": 'provider["registry.terraform.io/hashicorp/aws"]', "instances": []})
        instance = {"schema_version": schemas[kind]["version"],
                    "attributes": resource["change"]["after"], "sensitive_attributes": []}
        # Mock providers omit real-provider defaults; model a refreshed old state,
        # not those mock-only nulls. Production configuration is never rewritten.
        attributes = instance["attributes"]
        if "tags_all" in attributes:
            attributes["tags_all"] = attributes.get("tags") or {}
        defaults = {
            "aws_subnet": {"ipv6_native": False},
            "aws_instance": {"force_destroy": False, "get_password_data": False,
                             "source_dest_check": True, "user_data_replace_on_change": False},
            "aws_iam_role": {"force_detach_policies": False},
        }
        for key, value in defaults.get(kind, {}).items():
            if attributes.get(key) is None:
                attributes[key] = value
        if index is not None:
            instance["index_key"] = json.loads(index)
        group["instances"].append(instance)

    with tempfile.TemporaryDirectory(prefix="yon-module-check-") as directory:
        work = Path(directory)
        source = ROOT / "infra/terraform"
        config = work / "infra/terraform"
        shutil.copytree(source, config, ignore=shutil.ignore_patterns(".terraform", "tests", "*.tfstate*", "*.tfplan"))
        shutil.copytree(ROOT / "infra/inventory", work / "infra/inventory")
        (config / "provider_override.tf").write_text('''provider "aws" {
  access_key = "test"
  secret_key = "test"
  allowed_account_ids = []
  skip_credentials_validation = true
  skip_requesting_account_id = true
  skip_metadata_api_check = true
  endpoints {
    ec2 = "http://127.0.0.1:9"
    iam = "http://127.0.0.1:9"
    sts = "http://127.0.0.1:9"
    s3 = "http://127.0.0.1:9"
    cloudfront = "http://127.0.0.1:9"
  }
}
''', encoding="utf-8")
        state = work / "synthetic.tfstate"
        state.write_text(json.dumps({"version": 4, "terraform_version": "1.17.0", "serial": 1,
                                     "lineage": str(uuid.uuid4()), "outputs": {}, "resources": list(resources.values())}), encoding="utf-8")
        (work / "inventory").mkdir()
        inventory, _ = make_inventory(work / "inventory")
        inputs, targets = prepare(inventory)
        variables = work / "adoption.tfvars.json"
        variables.write_text(json.dumps({"adoption": inputs}), encoding="utf-8")

        def run(*args):
            result = subprocess.run([str(terraform), f"-chdir={config}", *args], text=True,
                                    capture_output=True, encoding="utf-8", errors="replace", timeout=180,
                                    env={**os.environ, "TF_IN_AUTOMATION": "1", "TF_INPUT": "0",
                                         "AWS_EC2_METADATA_DISABLED": "true"})
            if result.returncode:
                raise RuntimeError(f"Synthetic Terraform {args[0]} failed:\n{result.stdout[-6000:]}\n{result.stderr[-2000:]}")
            return result.stdout

        run("init", "-input=false", "-lockfile=readonly", f"-plugin-dir={source / '.terraform/providers'}",
            f"-backend-config=path={state}")
        saved = work / "move.tfplan"
        run("plan", "-refresh=false", "-input=false", f"-var-file={variables}", f"-out={saved}")
        moved = json.loads(run("show", "-json", str(saved)))
        check(moved, "import", targets)
        assert {r["previous_address"]: r["address"] for r in moved["resource_changes"] if r.get("previous_address")} == moves
        assert not any(r["change"].get("importing") for r in moved["resource_changes"])
        run("apply", "-input=false", str(saved))
        saved = work / "after.tfplan"
        run("plan", "-refresh=false", "-input=false", f"-var-file={variables}", f"-out={saved}")
        check(json.loads(run("show", "-json", str(saved))), "after", targets)
        print(f"PASS: {len(targets)} synthetic state objects, {len(moves)} exact moves, no AWS changes or pending imports; post-apply no changes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mock_log", type=Path)
    parser.add_argument("--terraform", type=Path, default=Path(shutil.which("terraform") or "terraform"))
    args = parser.parse_args()
    verify(args.mock_log, args.terraform.resolve())
