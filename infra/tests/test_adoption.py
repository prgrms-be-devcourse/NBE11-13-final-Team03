import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
import contextlib
import io
import subprocess
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))
import prepare_import
from import_fixture import make_inventory
import adopt_infrastructure
import collect_inventory


class AdoptionTests(unittest.TestCase):
    def test_current_roles_and_live_frontend_trust_are_preserved(self):
        expected = {"team03-main-sqs", "team03-data-sqs", "team03-frontend-deploy"}
        self.assertEqual(set(collect_inventory.IMPORT_ROLES), expected)
        self.assertNotIn("team03-github-actions-deploy", collect_inventory.ROLES)
        with tempfile.TemporaryDirectory() as path:
            file, raw = make_inventory(Path(path))
            trust = {"Version": "2012-10-17", "Statement": [{"Effect": "Allow",
                     "Principal": {"Federated": "arn:aws:iam::174170816230:oidc-provider/token.actions.githubusercontent.com"},
                     "Action": "sts:AssumeRoleWithWebIdentity", "Condition": {"StringEquals": {
                         "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
                         "token.actions.githubusercontent.com:sub": "repo:prgrms-be-devcourse@88020948/NBE11-13-final-Team03@1406626385:ref:refs/heads/main"}}}]}
            raw["results"]["roles/team03-frontend-deploy"]["data"]["Role"]["AssumeRolePolicyDocument"] = trust
            file.write_text(json.dumps(raw), encoding="utf-8")
            config, targets = prepare_import.prepare(file)
            self.assertEqual(set(config["roles"]), expected)
            self.assertEqual(json.loads(config["roles"]["team03-frontend-deploy"]["assume_role_policy"]), trust)
            self.assertFalse(any("team03-github-actions-deploy" in address for address in targets))

    def test_batch_refuses_a_different_existing_backend(self):
        with tempfile.TemporaryDirectory() as path:
            root = Path(path) / "checkout"
            backend = root / "infra/terraform/.terraform"
            backend.mkdir(parents=True)
            (backend / "terraform.tfstate").write_text(json.dumps({"backend": {"type": "local", "config": {"path": str(Path(path) / "other.tfstate")}}}))
            with patch.object(adopt_infrastructure, "ROOT", root):
                with self.assertRaisesRegex(ValueError, "Existing backend differs"):
                    adopt_infrastructure.execute(Path(path) / "state", apply=True)

    def test_prepares_all_groups_and_excludes_bootstrap_permissions(self):
        with tempfile.TemporaryDirectory() as path:
            file, _ = make_inventory(Path(path))
            config, targets = prepare_import.prepare(file)
            self.assertEqual(len(config["roles"]), 3)
            self.assertEqual(len(config["instances"]), 4)
            self.assertEqual(len(config["profiles"]), 2)
            self.assertEqual(len(config["inline_policies"]), 3)
            self.assertGreater(len(targets), 50)
            self.assertFalse(any("Temporary" in key for key in targets))
            self.assertEqual(config["bucket"]["versioning_status"], "Disabled")
            self.assertFalse(any(key.startswith("aws_sqs") for key in targets))

    def test_rejects_incomplete_inventory_other_accounts_errors_and_changed_rules(self):
        for mutation in ("incomplete", "account", "query", "rules"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as path:
                file, raw = make_inventory(Path(path))
                if mutation == "incomplete": raw["complete"] = False
                elif mutation == "account": raw["account_id"] = "000000000000"
                elif mutation == "query": raw["results"]["oidc"]["status"] = "error"
                else: raw["results"]["sg-rules"]["data"]["SecurityGroupRules"].pop()
                file.write_text(json.dumps(raw), encoding="utf-8")
                with self.assertRaises(ValueError): prepare_import.prepare(file)

    def test_absent_bucket_controls_are_not_created_and_denial_is_not_absence(self):
        with tempfile.TemporaryDirectory() as path:
            file, raw = make_inventory(Path(path))
            for name in ("get-bucket-policy", "get-public-access-block", "get-bucket-ownership-controls", "get-bucket-encryption"):
                raw["results"]["s3/" + name] = {"status": "absent"}
            file.write_text(json.dumps(raw), encoding="utf-8")
            config, targets = prepare_import.prepare(file)
            self.assertTrue(all(not control for control in config["bucket_controls"].values()))
            self.assertFalse(any("aws_s3_bucket_policy." in a for a in targets))
            raw["results"]["s3/get-bucket-policy"] = {"status": "error", "error_code": "AccessDenied"}
            file.write_text(json.dumps(raw), encoding="utf-8")
            with self.assertRaises(ValueError): prepare_import.prepare(file)

    def test_refuses_unpublished_function_changes_and_unknown_cloudfront_features(self):
        with tempfile.TemporaryDirectory() as path:
            directory = Path(path)
            file, _ = make_inventory(directory)
            (directory / "cf-function-0-development.js").write_text("unpublished change", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "LIVE/DEVELOPMENT"): prepare_import.prepare(file)
        with self.assertRaisesRegex(ValueError, "Unsupported"):
            prepare_import.distribution({"OriginGroups": {"Quantity": 1, "Items": [{}]}}, {})

    def test_all_target_actions_and_partial_state_are_checked(self):
        with tempfile.TemporaryDirectory() as path:
            file, _ = make_inventory(Path(path))
            _, targets = prepare_import.prepare(file)
            plan = {"complete": True, "resource_changes": [], "planned_values": {"root_module": {"resources": []}}}
            for address, expected in targets.items():
                plan["resource_changes"].append({"address": address, "mode": "managed", "provider_name": "registry.terraform.io/hashicorp/aws",
                                                 "change": {"actions": ["no-op"], "importing": {"id": expected["import_id"]}}})
                plan["planned_values"]["root_module"]["resources"].append({"address": address, "values": expected["values"]})
            self.assertEqual(len(prepare_import.expected_targets()), 27)
            import check_import_plan
            self.assertEqual(check_import_plan.check(plan, "import", targets), len(targets))
            prior = copy.deepcopy(plan["planned_values"]["root_module"]["resources"][0])
            plan["prior_state"] = {"values": {"root_module": {"resources": [prior]}}}
            plan["resource_changes"][0]["change"].pop("importing")
            self.assertEqual(check_import_plan.check(plan, "import", targets), len(targets))
            plan["resource_changes"][-1]["change"]["actions"] = ["update"]
            with self.assertRaisesRegex(ValueError, "forbidden"): check_import_plan.check(plan, "import", targets)

    def test_batch_never_applies_a_changed_plan_and_keeps_logs_private(self):
        for changed, moving in ((False, False), (True, False), (False, True)):
            with self.subTest(changed=changed, moving=moving), tempfile.TemporaryDirectory() as path:
                root = Path(path) / "checkout"
                (root / "infra/terraform").mkdir(parents=True)
                state = Path(path) / "state"
                calls = []
                applied = False

                class FakeCollector:
                    def collect(self, directory, scope):
                        directory.mkdir()
                        make_inventory(directory)
                        return True

                def run(command, **kwargs):
                    nonlocal applied
                    calls.append(command)
                    operation = command[2]
                    if operation == "version":
                        kwargs["stdout"].write(b'{"terraform_version":"1.17.0"}')
                    elif operation == "plan":
                        saved = Path(next(a[5:] for a in command if a.startswith("-out=")))
                        saved.write_bytes(b"saved-plan")
                        kwargs["stdout"].write(b"private-policy-and-header-content")
                    elif operation == "show":
                        run_dir = Path(command[-1]).parent
                        targets = json.loads((run_dir / "targets.json").read_text())
                        changes, resources, prior = [], [], []
                        from check_import_plan import ADDRESS_MOVES
                        moves = {v: k for k, v in json.loads(ADDRESS_MOVES.read_text()).items()}
                        for address, target in targets.items():
                            change = {"actions": ["update"] if changed else ["no-op"]}
                            if not moving and Path(command[-1]).stem == "import-plan": change["importing"] = {"id": target["import_id"]}
                            changes.append({"address": address, "mode": "managed", "provider_name": "registry.terraform.io/hashicorp/aws", "change": change})
                            values = {"id": target["import_id"], **target["values"]}
                            resources.append({"address": address, "values": values})
                            if moving:
                                previous = moves.get(address) if not applied else None
                                if previous: changes[-1]["previous_address"] = previous
                                prior.append({"address": previous or address, "values": values})
                        kwargs["stdout"].write(json.dumps({"complete": True, "resource_changes": changes, "planned_values": {"root_module": {"resources": resources}}, "prior_state": {"values": {"root_module": {"resources": prior}}}}).encode())
                    elif operation == "apply":
                        applied = True
                        (state / "terraform.tfstate").write_text("{}")
                    return subprocess.CompletedProcess(command, 0)

                output = io.StringIO()
                with patch.object(adopt_infrastructure, "ROOT", root), patch.object(adopt_infrastructure, "Collector", FakeCollector), \
                        patch.object(adopt_infrastructure.shutil, "which", return_value="terraform"), \
                        patch.object(adopt_infrastructure.subprocess, "run", side_effect=run), contextlib.redirect_stdout(output):
                    if changed:
                        with self.assertRaisesRegex(ValueError, "forbidden"): adopt_infrastructure.execute(state, apply=True)
                    else:
                        folder = adopt_infrastructure.execute(state, apply=True)
                        self.assertTrue((folder / "completed.json").exists())
                self.assertEqual(any(c[2] == "apply" for c in calls), not changed)
                self.assertNotIn("private-policy-and-header-content", output.getvalue())

    def test_complete_import_collection_is_covered_by_one_read_policy(self):
        import fnmatch
        with tempfile.TemporaryDirectory() as path:
            directory = Path(path)
            _, fixture = make_inventory(directory)
            responses = fixture["results"]
            actions = []

            def run(command, **kwargs):
                service, operation = command[1:3]
                actions.append(service + ":" + "".join(p.title() for p in operation.split("-")))
                key = None
                if service == "sts": key = "identity"
                elif service == "ec2":
                    key = {"describe-vpcs": "vpc", "describe-instances": "instances", "describe-volumes": "volumes", "describe-security-group-rules": "sg-rules", "describe-addresses": "eips", "describe-key-pairs": "key-pair"}.get(operation)
                    if operation == "describe-instance-attribute": key = "termination/" + command[command.index("--instance-id")+1]
                elif service == "iam":
                    if "--role-name" in command:
                        name = command[command.index("--role-name")+1]
                        prefix = {"get-role": "roles", "list-instance-profiles-for-role": "profiles", "list-attached-role-policies": "attached", "list-role-policies": "inline", "get-role-policy": "inline"}[operation]
                        key = prefix + "/" + name
                        if operation == "get-role-policy": key += "/" + command[command.index("--policy-name")+1]
                    elif "--policy-arn" in command:
                        key = ("policy-version/" if operation == "get-policy-version" else "policy/") + command[command.index("--policy-arn")+1]
                        if operation == "get-policy":
                            data = copy.deepcopy(responses[key]["data"])
                            data["Policy"]["DefaultVersionId"] = "v1"
                            return subprocess.CompletedProcess(command, 0, json.dumps(data), "")
                    elif operation == "get-instance-profile": key = "profile/" + command[command.index("--instance-profile-name")+1]
                    else: key = "oidc"
                elif service == "s3api": key = "s3/tags" if operation == "get-bucket-tagging" else "s3/" + operation
                elif service == "cloudfront":
                    if operation == "list-distributions": key = "cf-distributions"
                    elif operation == "get-distribution": key = "cloudfront/EIMPORTFIXTURE"
                    elif operation == "get-origin-access-control": key = "cf-oac"
                    elif operation == "list-tags-for-resource":
                        key = "cf-function/yon-spa-rewrite/tags" if ":function/" in command[command.index("--resource")+1] else "cloudfront/EIMPORTFIXTURE/tags"
                    elif operation == "describe-function": key = "cf-function/yon-spa-rewrite/" + ("development" if "DEVELOPMENT" in command else "config")
                    elif operation == "get-function":
                        stage = command[command.index("--stage")+1]
                        Path(command[command.index(stage)+1]).write_text("function handler(event) { return event.request; }\n", encoding="utf-8")
                return subprocess.CompletedProcess(command, 0, json.dumps(responses[key]["data"] if key else {}), "")

            with patch.object(collect_inventory.subprocess, "run", side_effect=run):
                output = directory / "collected"
                self.assertTrue(collect_inventory.Collector().collect(output, "import"))
                config, targets = prepare_import.prepare(output / "inventory.json")
                self.assertEqual(len(config["roles"]), 3)
                self.assertGreater(len(targets), 50)
            policy = json.loads((Path(__file__).parents[1] / "iam/terraform-import-read.json").read_text())
            granted = [a.lower() for statement in policy["Statement"] for a in statement["Action"]]
            for action in actions:
                if action.lower() == "sts:getcalleridentity": continue
                if action.startswith("s3api:"):
                    action = action.replace("s3api:", "s3:").replace("GetPublicAccessBlock", "GetBucketPublicAccessBlock").replace("GetBucketEncryption", "GetEncryptionConfiguration")
                self.assertTrue(any(fnmatch.fnmatchcase(action.lower(), allowed) for allowed in granted), action)
            self.assertTrue(all(a.split(":")[1].startswith(("get", "list", "describe")) for a in granted))
            self.assertTrue({"s3:getencryptionconfiguration", "s3:getaccelerateconfiguration", "s3:getlifecycleconfiguration", "s3:getreplicationconfiguration"}.issubset(granted))


if __name__ == "__main__":
    unittest.main()
