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

    def test_cloudfront_values_match_provider_read_back(self):
        with tempfile.TemporaryDirectory() as path:
            file, raw = make_inventory(Path(path))
            distro = next(v for k, v in raw["results"].items() if k.startswith("cloudfront/") and not k.endswith("/tags"))
            config = distro["data"]["Distribution"]["DistributionConfig"]
            config["Comment"] = ""
            config["Origins"]["Items"].append({"Id": "frontend-s3", "DomainName": "bucket.s3.ap-northeast-2.amazonaws.com",
                                               "ConnectionAttempts": 3, "ConnectionTimeout": 10, "OriginAccessControlId": "E2JJZ8SZNVRWDC",
                                               "S3OriginConfig": {"OriginAccessIdentity": ""}})
            file.write_text(json.dumps(raw), encoding="utf-8")
            prepared, _ = prepare_import.prepare(file)
            distribution = prepared["distribution"]
            self.assertIsNone(distribution["comment"])
            self.assertIsNone(distribution["viewer_certificate"]["ssl_support_method"])
            origins = {o["origin_id"]: o for o in distribution["origins"]}
            self.assertIsNone(origins["yon-backend"]["origin_access_control_id"])
            self.assertEqual(origins["frontend-s3"]["s3_origin_config"], [])
            self.assertEqual(origins["frontend-s3"]["origin_access_control_id"], "E2JJZ8SZNVRWDC")

    def test_oidc_snapshot_url_matches_provider_state(self):
        with tempfile.TemporaryDirectory() as path:
            file, _ = make_inventory(Path(path))
            config, targets = prepare_import.prepare(file)
            self.assertEqual(config["oidc"]["url"], "https://token.actions.githubusercontent.com")
            self.assertEqual(targets["module.github_oidc.aws_iam_openid_connect_provider.this"]["values"]["url"], "token.actions.githubusercontent.com")

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

    def test_rebind_requires_exact_reviewed_snapshot_and_stale_flat_binding(self):
        from check_import_plan import (expected_targets, MAIN_HTTP_SOURCE, MAIN_HTTP_TARGET,
                                       MAIN_HTTP_NEW_ID, MAIN_HTTP_OLD_ID, MAIN_HTTP_PREFIX)
        targets = copy.deepcopy(expected_targets())
        target = targets[MAIN_HTTP_TARGET]
        target["import_id"] = MAIN_HTTP_NEW_ID
        target["values"].update(id=MAIN_HTTP_NEW_ID, cidr_ipv4=None, prefix_list_id=MAIN_HTTP_PREFIX)
        attributes = {**target["values"], "id": MAIN_HTTP_OLD_ID, "cidr_ipv4": "0.0.0.0/0", "prefix_list_id": None}
        raw = {"resources": [{"mode": "managed", "type": "aws_vpc_security_group_ingress_rule", "name": "existing",
                              "instances": [{"index_key": "main_http", "attributes": attributes}]}]}
        with tempfile.TemporaryDirectory() as path:
            state = Path(path) / "terraform.tfstate"
            state.write_text(json.dumps(raw), encoding="utf-8")
            original = state.read_bytes()
            result = adopt_infrastructure.main_http_rebind(state, targets)
            self.assertEqual(result, {"address": MAIN_HTTP_SOURCE, "old_id": MAIN_HTTP_OLD_ID, "new_id": MAIN_HTTP_NEW_ID})
            self.assertEqual(state.read_bytes(), original)
            unreviewed = copy.deepcopy(targets)
            unreviewed[MAIN_HTTP_TARGET]["values"]["prefix_list_id"] = None
            with self.assertRaises(ValueError): adopt_infrastructure.main_http_rebind(state, unreviewed)
            for key, value in (("id", "sgr-unapproved"), ("from_port", 22), ("cidr_ipv4", "10.0.0.0/8")):
                broken = copy.deepcopy(raw)
                broken["resources"][0]["instances"][0]["attributes"][key] = value
                state.write_text(json.dumps(broken), encoding="utf-8")
                with self.assertRaises(ValueError): adopt_infrastructure.main_http_rebind(state, targets)
            raw["resources"][0]["instances"][0]["attributes"]["id"] = MAIN_HTTP_NEW_ID
            state.write_text(json.dumps(raw), encoding="utf-8")
            self.assertIsNone(adopt_infrastructure.main_http_rebind(state, targets))

    def test_rebind_preview_preserves_real_state_and_changed_plan_never_forgets(self):
        from check_import_plan import expected_targets, ADDRESS_MOVES, MAIN_HTTP_SOURCE, MAIN_HTTP_TARGET, MAIN_HTTP_OLD_ID
        targets = expected_targets()
        for changed, apply in ((False, False), (True, True), (False, True)):
            with self.subTest(changed=changed, apply=apply), tempfile.TemporaryDirectory() as path:
                root = Path(path) / "checkout"
                (root / "infra/terraform").mkdir(parents=True)
                state_dir = Path(path) / "state"
                state_dir.mkdir()
                state = state_dir / "terraform.tfstate"
                old_values = {**targets[MAIN_HTTP_TARGET]["values"], "id": MAIN_HTTP_OLD_ID,
                              "cidr_ipv4": "0.0.0.0/0", "prefix_list_id": None}
                state.write_text(json.dumps({"resources": [{"mode": "managed", "type": "aws_vpc_security_group_ingress_rule",
                    "name": "existing", "instances": [{"index_key": "main_http", "attributes": old_values}]}]}), encoding="utf-8")
                original = state.read_bytes()
                calls = []
                class Collector:
                    def collect(self, *_): return True
                def run(command, **kwargs):
                    calls.append(command)
                    operation = command[2]
                    if operation == "version": kwargs["stdout"].write(b'{"terraform_version":"1.17.0"}')
                    elif operation == "state":
                        self.assertEqual(command[3], "rm")
                        self.assertEqual(command[-1], MAIN_HTTP_SOURCE)
                        # Planning uses only a private copy, even when --apply later rejects its preview.
                        if not any(x.startswith("-state=") for x in command):
                            self.assertTrue(apply and not changed)
                            state.write_text('{"resources": []}', encoding="utf-8")
                        else:
                            self.assertTrue(any(x.startswith("-state=") and "rebind-preview.tfstate" in x for x in command))
                    elif operation == "plan": Path(next(x[5:] for x in command if x.startswith("-out="))).write_bytes(b"plan")
                    elif operation == "show":
                        moves = {v: k for k, v in json.loads(ADDRESS_MOVES.read_text()).items()}
                        resources, changes, prior = [], [], []
                        for address, target in targets.items():
                            values = {"id": target["import_id"], **target["values"]}
                            resources.append({"address": address, "values": values})
                            change = {"address": address, "mode": "managed", "provider_name": "registry.terraform.io/hashicorp/aws",
                                      "change": {"actions": ["update"] if changed else ["no-op"]}}
                            if Path(command[-1]).stem == "after-plan":
                                prior.append({"address": address, "values": values})
                            elif address == MAIN_HTTP_TARGET: change["change"]["importing"] = {"id": target["import_id"]}
                            else:
                                change["previous_address"] = moves[address]
                                prior.append({"address": moves[address], "values": values})
                            changes.append(change)
                        kwargs["stdout"].write(json.dumps({"complete": True, "resource_changes": changes,
                            "planned_values": {"root_module": {"resources": resources}},
                            "prior_state": {"values": {"root_module": {"resources": prior}}}}).encode())
                    elif operation == "apply":
                        self.assertTrue(apply and not changed)
                        updated = copy.deepcopy(old_values)
                        updated.update(targets[MAIN_HTTP_TARGET]["values"])
                        state.write_text(json.dumps({"resources": [{"module": "module.main", "mode": "managed",
                            "type": "aws_vpc_security_group_ingress_rule", "name": "this",
                            "instances": [{"index_key": "main_http", "attributes": updated}]}]}), encoding="utf-8")
                    return subprocess.CompletedProcess(command, 0)
                with patch.object(adopt_infrastructure, "ROOT", root), patch.object(adopt_infrastructure, "Collector", Collector), \
                     patch.object(adopt_infrastructure, "prepare", return_value=({}, targets)), \
                     patch.object(adopt_infrastructure.shutil, "which", return_value="terraform"), \
                     patch.object(adopt_infrastructure.subprocess, "run", side_effect=run), contextlib.redirect_stdout(io.StringIO()):
                    if changed:
                        with self.assertRaisesRegex(ValueError, "forbidden"):
                            adopt_infrastructure.execute(state_dir, apply=True, rebind_main_http=True)
                    else: adopt_infrastructure.execute(state_dir, apply=apply, rebind_main_http=True)
                if apply and not changed:
                    self.assertNotEqual(state.read_bytes(), original)
                    self.assertEqual(sum(c[2] == "state" for c in calls), 2)
                    self.assertIsNone(adopt_infrastructure.main_http_rebind(state, targets))
                else:
                    self.assertEqual(state.read_bytes(), original)
                    self.assertEqual(sum(c[2] == "state" for c in calls), 1)

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
