import copy
import importlib.util
import json
import re
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("guard", Path(__file__).parents[1] / "scripts/check_import_plan.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


def valid_plan(phase="import"):
    changes, resources = [], []
    for address, target in guard.expected_targets().items():
        change = {"actions": ["no-op"]}
        if phase == "import":
            change["importing"] = {"id": target["import_id"]}
        changes.append({"address": address, "mode": "managed",
                        "provider_name": "registry.terraform.io/hashicorp/aws", "change": change})
        resources.append({"address": address, "values": copy.deepcopy(target["values"])})
    return {"complete": True, "errored": False, "resource_changes": changes,
            "planned_values": {"root_module": {"resources": resources}}}


class ImportPlanTests(unittest.TestCase):
    def test_nested_module_values_and_exact_state_moves(self):
        plan = valid_plan("after")
        moved_from = {v: k for k, v in json.loads(guard.ADDRESS_MOVES.read_text()).items()}
        planned = plan["planned_values"]["root_module"].pop("resources")
        prior = copy.deepcopy(planned)
        for resource, before, change in zip(planned, prior, plan["resource_changes"]):
            resource["values"].setdefault("id", guard.expected_targets()[resource["address"]]["import_id"])
            before["values"] = copy.deepcopy(resource["values"])
            before["address"] = moved_from[resource["address"]]
            change["previous_address"] = before["address"]
        plan["planned_values"]["root_module"]["child_modules"] = [{"child_modules": [{"resources": planned}]}]
        plan["prior_state"] = {"values": {"root_module": {"resources": prior}}}
        self.assertEqual(guard.check(plan, "import"), 27)
        for mutation in ("wrong_source", "import", "changed_id", "after", "duplicate"):
            with self.subTest(mutation=mutation):
                broken = copy.deepcopy(plan)
                if mutation == "wrong_source": broken["resource_changes"][0]["previous_address"] = "aws_vpc.unapproved"
                elif mutation == "import": broken["resource_changes"][0]["change"]["importing"] = {"id": "vpc-any"}
                elif mutation == "changed_id": broken["prior_state"]["values"]["root_module"]["resources"][0]["values"]["id"] = "vpc-other"
                elif mutation == "duplicate": broken["planned_values"]["root_module"]["child_modules"].append({"resources": [copy.deepcopy(planned[0])]})
                with self.assertRaises(ValueError): guard.check(broken, "after" if mutation == "after" else "import")

    def test_only_approved_http_reimport_may_accompany_moves(self):
        targets = copy.deepcopy(guard.expected_targets())
        target = targets[guard.MAIN_HTTP_TARGET]
        target["import_id"] = guard.MAIN_HTTP_NEW_ID
        target["values"].update(id=guard.MAIN_HTTP_NEW_ID, cidr_ipv4=None, prefix_list_id=guard.MAIN_HTTP_PREFIX)
        plan = valid_plan("after")
        previous = {v: k for k, v in json.loads(guard.ADDRESS_MOVES.read_text()).items()}
        prior = []
        for resource, change in zip(plan["planned_values"]["root_module"]["resources"], plan["resource_changes"]):
            resource["values"] = copy.deepcopy(targets[resource["address"]]["values"])
            resource["values"].setdefault("id", targets[resource["address"]]["import_id"])
            if resource["address"] == guard.MAIN_HTTP_TARGET:
                change["change"]["importing"] = {"id": guard.MAIN_HTTP_NEW_ID}
            else:
                change["previous_address"] = previous[resource["address"]]
                prior.append({"address": previous[resource["address"]], "values": copy.deepcopy(resource["values"])})
        plan["prior_state"] = {"values": {"root_module": {"resources": prior}}}
        self.assertEqual(guard.check(plan, "import", targets, rebind_main_http=True), len(targets))
        with self.assertRaisesRegex(ValueError, "Do not mix"):
            guard.check(plan, "import", targets)
        for mutation in ("extra_import", "wrong_id", "wide_source", "not_forgotten", "update", "after"):
            with self.subTest(mutation=mutation):
                broken, expected = copy.deepcopy(plan), copy.deepcopy(targets)
                main = next(r for r in broken["resource_changes"] if r["address"] == guard.MAIN_HTTP_TARGET)
                if mutation == "extra_import": broken["resource_changes"][0]["change"]["importing"] = {"id": "unexpected"}
                elif mutation == "wrong_id": main["change"]["importing"]["id"] = guard.MAIN_HTTP_OLD_ID
                elif mutation == "wide_source": expected[guard.MAIN_HTTP_TARGET]["values"].update(cidr_ipv4="0.0.0.0/0")
                elif mutation == "not_forgotten": broken["prior_state"]["values"]["root_module"]["resources"].append({"address": guard.MAIN_HTTP_SOURCE, "values": {"id": guard.MAIN_HTTP_OLD_ID}})
                elif mutation == "update": main["change"]["actions"] = ["update"]
                with self.assertRaises(ValueError):
                    guard.check(broken, "after" if mutation == "after" else "import", expected, rebind_main_http=True)

    def test_move_manifest_matches_retained_hcl_declarations(self):
        moves = json.loads(guard.ADDRESS_MOVES.read_text())
        text = (guard.ADDRESS_MOVES.parents[1] / "terraform/moved.tf").read_text()
        declarations = re.findall(r"moved\s*\{\s*from\s*=\s*(.+?)\s*\n\s*to\s*=\s*(.+?)\s*\n\}", text)
        self.assertEqual(dict(declarations), moves)
        self.assertEqual(len(moves), len(set(moves.values())))
        self.assertFalse(any("aws_iam_role_policy." in a or "aws_iam_role_policy_attachment." in a for a in moves))

    def test_accepts_exact_import_and_after_import_no_change(self):
        for phase in ("import", "after"):
            self.assertEqual(guard.check(valid_plan(phase), phase), 27)

    def test_rejects_all_aws_actions_including_replacement_and_read(self):
        for actions in (["create"], ["update"], ["delete"], ["delete", "create"], ["read"]):
            with self.subTest(actions=actions):
                plan = valid_plan()
                plan["resource_changes"][0]["change"]["actions"] = actions
                with self.assertRaisesRegex(ValueError, "forbidden"):
                    guard.check(plan, "import")

    def test_rejects_errored_partial_deferred_and_drifted_plan(self):
        for field, value in (("errored", True), ("complete", False), ("complete", None),
                             ("deferred_changes", [{"reason": "unknown"}]),
                             ("resource_drift", [{"address": "module.network.aws_vpc.this"}])):
            with self.subTest(field=field, value=value):
                plan = valid_plan()
                plan[field] = value
                with self.assertRaises(ValueError):
                    guard.check(plan, "import")

    def test_accepts_only_known_read_only_drift(self):
        def drift(kind, before, after, actions=("update",), address=None):
            return {"address": address or f"module.data.{kind}.this", "type": kind,
                    "change": {"actions": list(actions), "before": before, "after": after}}
        role = lambda *names: {"inline_policy": [{"name": n} for n in names]}
        accepted = [
            drift("aws_instance", {"public_ip": "13.124.235.106", "public_dns": "a", "ami": "x"},
                  {"public_ip": "3.34.42.141", "public_dns": "b", "ami": "x"}),
            drift("aws_security_group", {"ingress": [{"from_port": 8080}]}, {"ingress": [{"from_port": 8080, "x": 1}]}),
            drift("aws_iam_role", role("YONNetworkInventoryReadTemporary", "team03-main-sqsPolicy"),
                  role("YONTerraformImportReadTemporary", "team03-main-sqsPolicy")),
        ]
        plan = valid_plan()
        plan["resource_drift"] = accepted
        guard.check(plan, "import")
        self.assertEqual(guard.check_drift(accepted), 3)

        rejected = [
            drift("aws_instance", {"public_ip": "1", "instance_type": "t3.small"}, {"public_ip": "2", "instance_type": "t3.large"}),
            drift("aws_instance", {"public_ip": "1"}, None, actions=("delete",)),
            drift("aws_iam_role", role("team03-main-sqsPolicy"), role("team03-main-sqsPolicy", "Unexpected")),
            drift("aws_vpc", {"cidr_block": "a"}, {"cidr_block": "b"}),
            {"address": "module.network.aws_vpc.this"},
        ]
        for item in rejected:
            with self.subTest(item=item["address"]):
                plan = valid_plan()
                plan["resource_drift"] = [item]
                with self.assertRaises(ValueError):
                    guard.check(plan, "import")

    def test_temporary_policy_names_match_inventory_collector(self):
        spec_inventory = importlib.util.spec_from_file_location(
            "inventory_for_guard", Path(__file__).parents[1] / "scripts/collect_inventory.py")
        inventory = importlib.util.module_from_spec(spec_inventory)
        spec_inventory.loader.exec_module(inventory)
        self.assertEqual(guard.TEMPORARY_POLICIES, frozenset(inventory.TEMPORARY_POLICIES))

    def test_rejects_missing_extra_and_duplicate_targets(self):
        for field in ("changes", "values"):
            for mutation in ("missing", "extra", "duplicate"):
                with self.subTest(field=field, mutation=mutation):
                    plan = valid_plan()
                    entries = (plan["resource_changes"] if field == "changes" else
                               plan["planned_values"]["root_module"]["resources"])
                    if mutation == "missing":
                        entries.pop()
                    else:
                        extra = copy.deepcopy(entries[0])
                        if mutation == "extra":
                            extra["address"] = "aws_instance.unexpected"
                        entries.append(extra)
                    with self.assertRaises(ValueError):
                        guard.check(plan, "import")

    def test_rejects_wrong_or_missing_import_id_and_remaining_import(self):
        for importing in (None, {"id": "vpc-wrong"}):
            plan = valid_plan()
            plan["resource_changes"][0]["change"]["importing"] = importing
            with self.assertRaisesRegex(ValueError, "import ID"):
                guard.check(plan, "import")
        with self.assertRaisesRegex(ValueError, "Import still pending"):
            guard.check(valid_plan(), "after")

    def test_rejects_changed_snapshot_values_even_with_no_op_actions(self):
        for field, value in (("id", "vpc-wrong"), ("region", "us-east-1"),
                             ("cidr_block", "10.1.0.0/16"), ("tags", {"Name": "changed"})):
            with self.subTest(field=field):
                plan = valid_plan()
                plan["planned_values"]["root_module"]["resources"][0]["values"][field] = value
                with self.assertRaisesRegex(ValueError, "Snapshot mismatch"):
                    guard.check(plan, "import")

    def test_rejects_data_sources_other_providers_and_address_moves(self):
        for field, value in (("mode", "data"), ("provider_name", "example/other"),
                             ("previous_address", "aws_vpc.old")):
            plan = valid_plan()
            plan["resource_changes"][0][field] = value
            with self.assertRaises(ValueError):
                guard.check(plan, "import")

    def test_special_import_ids_and_unrestricted_egress(self):
        targets = guard.expected_targets()
        self.assertEqual(targets["module.network.aws_route_table.main"]["import_id"], "rtb-0b7d3d03690c74791")
        self.assertEqual(targets["module.network.aws_route.this"]["import_id"], "rtb-0e5380dd325267c16_0.0.0.0/0")
        self.assertEqual(targets['module.network.aws_route_table_association.this["public_a"]']["import_id"],
                         "subnet-084d01b4bc4420dc4/rtb-0e5380dd325267c16")
        egress = [t for a, t in targets.items() if "aws_vpc_security_group_egress_rule." in a]
        self.assertEqual(len(egress), 4)
        self.assertTrue(all(t["values"]["from_port"] is None and t["values"]["to_port"] is None for t in egress))

    def test_supplemental_policy_has_only_three_regional_read_actions(self):
        path = Path(__file__).parents[1] / "iam/network-import-read.json"
        policy = json.loads(path.read_text(encoding="utf-8"))
        actions = set()
        for statement in policy["Statement"]:
            self.assertEqual(statement["Effect"], "Allow")
            self.assertEqual(statement["Condition"]["StringEquals"]["aws:RequestedRegion"], "ap-northeast-2")
            actions.update(statement["Action"])
        self.assertEqual(actions, {"ec2:DescribeNetworkAcls", "ec2:DescribeTags", "ec2:DescribeVpcAttribute"})
        self.assertEqual(policy["Statement"][1]["Resource"],
                         "arn:aws:ec2:ap-northeast-2:174170816230:vpc/vpc-032bd9d88d5c97147")


if __name__ == "__main__":
    unittest.main()
